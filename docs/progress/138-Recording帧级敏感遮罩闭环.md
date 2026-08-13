# Recording 帧级敏感遮罩闭环

> 完成日期：2026-08-13
> 关联能力：`recordingRedaction=frame-mask-v1`

## 本轮完成

1. Pixel Recording 不再把 CDP `Page.screencastFrame` 原始 JPEG 直接写入分段文件。Node
   对每一帧先用独立 `Runtime.evaluate` 读取有界敏感矩形，再在 Rust 内解码 JPEG、覆盖
   像素并重新编码；只有处理后的 Base64 才能进入有界写队列。
2. 识别规则覆盖 Password、OTP、Payment、Account、Secret、PII、显式敏感属性、Shadow
   Root 与 iframe。Node 只读取矩形、视口和计数，不上传 DOM 值、字段名、URL、正文或
   截图原始字节。
3. 扫描超过 10,000 个元素、区域超过 256、CDP 超时、响应缺失/版本错误、非法矩形、
   Base64/JPEG 解码失败、非 L8/RGB24 像素格式、1920×1080 上限或重新编码失败均
   fail-closed。当前原始 CDP 帧会先 ACK，随后录制终止；原帧不会进入队列，也不会生成
   Recording 完成标记。
4. 每条 NDJSON 帧新增 `redactionState`、`redactedRegionCount` 和
   `redactionPolicyVersion`。分段提交与最终 Manifest 同时持久化遮罩帧数、区域数和策略
   版本；Storage Helper 在上传前逐行解析 NDJSON 并核对每帧证明与汇总，Session Recorder
   再校验 Helper ACK，不接受伪造汇总或旧 Helper 静默忽略字段。
5. Node 仅在 Storage Helper 与 Object Storage 同时可用时声明
   `recordingRedaction=frame-mask-v1`。Control Plane 对请求 Pixel Recording 的首次放置、
   重启和跨 Node 迁移统一要求此能力，滚动升级期间不会落到 N−1 Node；没有合格 Node
   时返回 `NO_RECORDING_REDACTION_CAPABLE_NODE`。
6. Web/Tauri 共用创建向导和资源详情文案已明确“先遮罩、后提交；失败不上传原帧”，
   不再把“独立 CDP + Storage Helper”误写成已具备隐私防护的充分条件。

## 验证证据

- Rust Workspace 全量测试通过：Browser Node 共 107 个测试通过、2 个目标环境测试按
  条件忽略；新增真实 JPEG 全帧遮罩、原始 JPEG 不同、像素值覆盖、失败帧 ACK、失败帧
  不入队与失败状态保持测试。
- Java 定向测试通过：`BrowserCapacityApplicationServiceTest` 覆盖缺少能力时拒绝和具备
  `frame-mask-v1` 时成功放置。
- Rust Workspace `cargo check`、`cargo fmt` 与测试编译通过；Web 构建和完整仓库 Gate
  结果见本轮提交记录。

## 安全边界

- 这是语义/属性驱动的像素遮罩，不是 OCR，也不宣称能识别没有任何语义标记的任意视觉
  文本。
- 录屏期间不冻结页面脚本，避免破坏业务行为。动态 DOM 每帧重新计算矩形；计算失败时
  宁可终止录制，也不回退上传原帧。
- 分段对象已有哈希与 commit-last 标记，本轮把遮罩计数和策略版本写入分段及最终标记；
  对象层 WORM、Retention/Legal Hold 深度联动、播放授权与删除 Receipt 仍需单独完成。

## 仍未完成

1. 无语义视觉文本/OCR 分类，以及 Workspace/Site 可配置选择器策略；
2. Recording 对象 WORM/Retention/Legal Hold、Purpose-bound 播放、删除与审计闭环；
3. 目标 Linux 多 Session、高频 DOM、对象存储背压、磁盘满和长期录像压力证书。
