# Challenge 视觉截图像素隐私与状态围栏闭环

日期：2026-09-12。承接 progress 165 的 A05；本切片关闭通用 Challenge 视觉外发前的像素隐私缺口。

## 最小区域与精确状态围栏

- IMAGE_SELECTION/PUZZLE 只有在当前完整 Browser State 中存在可见、可操作、非敏感且带有界
  Visual Anchor 的 Challenge Target 时才进入 Vision；无安全区域、深度受限或状态不一致时以
  `CHALLENGE_PRIVACY_SAFE_REGION_UNAVAILABLE` fail-closed，并保留既有 Human Handoff；
- Control Plane 在截图前重读 Tenant/Session/Context、State Version/Hash、Target Revision 和
  Active Tab。新增的 `CHALLENGE_REGION` Node 命令只允许不超过 2048×2048、总面积不超过
  2,097,152 CSS pixels 的裁剪；Node 在真实捕获前再次采集页面状态并逐项重验；
- 截图 Evidence 新增 `CHALLENGE_SCREENSHOT` 类型，提交事件必须带同一 Evidence ID、捕获时间、
  State/Target/Tab 和 Region 元数据。Control Plane 只在精确匹配时把 Job 从 CAPTURING 推到
  READY；执行动作前再次重验捕获状态；
- 模型输出仍使用裁剪图的 0—1 坐标，控制面按已持久化裁剪区域映射回完整 Viewport 坐标后再
  交给 Node，避免裁剪后点击落在错误位置。旧 Observer 截图命令不带新增字段，保持 N−1 兼容。

## 本地 OCR/PII 门禁

隔离 Vision Worker 在调用外部模型前执行固定本机 Tesseract TSV OCR，检测邮箱、电话号码、
SSN、API Key/Token、OTP、JWT 和通过 Luhn 校验的银行卡号。命中项按 OCR 行聚合为有界矩形，
由固定 ImageMagick 二进制在内存/面积限制下覆盖为黑色、移除元数据并重新编码 JPEG；随后第二次
OCR 复核。OCR、重编码、超时或复核任一步失败，以及脱敏图仍有敏感模式时，均不调用模型。

Worker Claim 必须同时声明 `screenshot-ocr-actions-v1` 与 `local-ocr-pii-gate-v1`；缺失新能力返回
稳定 409 `VISION_PRIVACY_CAPABILITY_MISSING`。Complete 必须提交固定扫描版本、OCR 文本
SHA-256、检测/遮罩数量和剩余数量为零的证明。PostgreSQL、API、Audit 和模型请求均不包含 OCR
原文；外部模型只收到裁剪并经本地复核的 JPEG。原始裁剪像素仍按既有 Purpose-bound 一次性
Evidence Grant 在受控对象存储与隔离 Worker 间传递，本切片不把它误称为“从不落对象存储”。

## V119 安全迁移与回滚

V119 只增加 nullable 列、允许新的 Evidence kind，并以 `NOT VALID` 后 `VALIDATE` 增加完整组
约束；没有回填、重写旧 Job 或增加 NOT NULL。旧记录保持全 NULL 合法，新记录的隐私证明与
捕获范围必须整组有效。N−1 应用忽略新增列；新 Control Plane 对没有范围元数据的旧 Node
Challenge 截图拒绝进入 Vision，不会为兼容而外发整页像素。

部署顺序为先迁移、再 Control Plane/Node/Worker；验证数据库约束、Worker 双能力 Claim、真实
截图元数据及模型前本地扫描。若应用回滚，保留 V119 schema 并回滚二进制即可；不要在生产流量
中删除列或旧约束。恢复新版本后可继续处理新 Job，旧版留下的全 NULL Job 仍按兼容规则处理。

## 验证

- Control Plane 539 项、Web 141 项、Agent/Reviewer/Vision/Outcome Worker 30 项，以及 Rust
  Workspace/Clippy/fmt、Go Provider、Operator、供应链和 50k Coordinator Gate 通过；
- Vision 单测覆盖 OCR 敏感模式、Luhn、裁剪遮罩、二次 OCR 残留 fail-closed、工具失败时模型
  零调用和双能力 Claim；本机真实 Tesseract 安全文本样例通过；
- OpenAPI lint、245 Operations / 338 Schemas 的 TypeScript/Python/Go/Java SDK 生成、编译、
  打包与发布清单通过；Protobuf additive 字段与 V119 N/N−1 Gate 通过；
- Desktop test/lint/unsigned build 通过；完整 PostgreSQL/Redis/MinIO/mTLS/真实 Chromium
  Integration 输出 `challenge_visual_automation=true` 与 `challenge_visual_pixel_privacy=true`，
  并覆盖缺少本地隐私能力的 409、迁移、状态围栏、对象存储及其余恢复主链；
- 本机单独构建 Worker 镜像时 Docker Hub token endpoint 两次超时，属于外部网络取镜像失败；
  不以本地失败冒充通过，最终容器构建与供应链结果以本提交 GitHub CI 记录为准。

## 边界

- A05 仓库内通用 Challenge 截图裁剪、OCR 可见 PII 检测/像素遮罩、二次复核和不可验证拒绝
  已关闭。没有安全 Target 的 Canvas、图片、PDF 或跨域不透明内容不会回退整页 Vision，而是
  Human Handoff；A19 继续负责 Opaque Cross-Origin Frame 的专用策略；
- OCR 无法证明不存在人脸、证件图案等非文本视觉敏感信息。裁剪把暴露面限制在明确 Challenge
  Target；目标模型/地区的数据处理协议、客户数据集 Replay 与人工复核仍属于生产准入 Gate；
- Recording 全帧 OCR、对象 WORM/Retention/Legal Hold 深度联动和 Purpose-bound 播放仍是
  独立剩余项，不由本切片提前关闭。
