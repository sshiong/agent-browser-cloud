# Recording 全帧 OCR 与非文本视觉隐私闭环

> 日期：2026-09-22
> 范围：Browser Node Recording、隔离本地扫描器、Storage Helper、调度能力围栏

## 结论

Recording 不再只依赖 DOM 语义区域。每一张实际进入 Segment 的帧都会先完成既有 DOM 遮罩，
随后通过 Node 本地持久扫描进程执行全帧 Tesseract OCR/PII、人脸和二维码检测、像素遮罩与二次
零残留复检。任一工具不可用、输出越界、证明不一致或复检仍有信号时，当前帧不会进入写队列，
Recording fail-closed 终止。

该结论关闭仓库内“全帧 OCR/非文本视觉分类”代码缺口，但不把有限分类器说成绝对 DLP：侧脸、
证件版式、医学影像及客户特有视觉类别仍需租户策略、目标数据集 Replay 和人工复核共同治理。

## 实现

1. `recording_privacy_scanner.py` 只通过 stdin/stdout 处理有界 JPEG，不创建临时像素或 OCR 文本
   文件。一个 Recording 复用一个隔离进程，避免逐帧重复加载 OpenCV；stderr 被丢弃，OCR 文本
   不进入 NDJSON、日志、数据库或审计。
2. OCR 覆盖邮箱、电话、SSN、Secret/Token/API Key、OTP/JWT 和 Luhn 支付卡信号；非文本分类
   覆盖 OpenCV Haar 正面人脸和 QR 几何区域。检测区域统一在像素层覆盖后重新编码，再完整执行
   OCR、Face 与 QR 复检，残留必须为零。
3. CDP `Page.screencastFrameAck` 在全帧扫描结束后发送，使 Chromium 协议本身成为背压边界；
   失败帧仍先 ACK 再终止，避免浏览器卡死，但原始或半处理像素永不进入有界 Frame Queue。
4. 每帧 NDJSON 使用 `redactionPolicyVersion=2`，保存最小化的扫描版本、检测/遮罩/残留计数和
   最终像素 SHA-256。Storage Helper 上传前逐行重算像素 Hash、验证计数关系和零残留证明；v1
   历史 Recording 继续可播放，v2 新录制不接受降级证明。
5. Node 只有在对象存储可用且扫描器功能自检成功时才声明
   `recordingRedaction=full-frame-privacy-v2`。Control Plane 首次放置、重启和迁移均要求该精确
   能力；缺少 Tesseract、语言包、OpenCV 分类器或功能测试失败的 Node 不承接 Recording。
6. Browser Node 镜像安装固定的 Tesseract 英文/简体中文语言包与 OpenCV 分类数据，并在镜像
   构建阶段运行包含真实 OCR 和 QR 检测/遮罩/复检的功能自检。
7. AWS SDK 仅保留现代 `default-https-client` TLS 路径，移除重复的旧 `rustls`
   feature；`Cargo.lock` 不再包含受 `GHSA-82j2-j2ch-gfr8` 影响的
   `rustls-webpki 0.101.7`，当前只保留 `rustls-webpki 0.103.13`。

## 验证

- Rust 定向测试覆盖扫描进程流协议、失败帧 ACK、不入队、v2 每帧证明、像素 Hash 篡改拒绝和
  v1 历史兼容；Control Plane 定向测试覆盖只接受 v2 能力的放置逻辑。
- OrbStack Browser Node release 镜像在构建阶段真实运行 Tesseract/OpenCV 功能自检，确认 OCR
  与 QR 信号均被检测、遮罩且复检为零。
- 完整 Integration 使用真实 PostgreSQL/Redis/MinIO/mTLS 链验证 v2 能力发现、Recording
  Segment/Manifest、Playback、Retention 删除及其余恢复主链。Integration 内的 Fake Chromium
  使用协议夹具隔离 Node↔Scanner 流协议；真实扫描算法由 release 镜像功能 Gate 独立证明。
- 最终 `make ci` 全量通过，包含 Rust Workspace/Clippy、Control Plane、Web 143 项、
  Worker/Compose、OpenAPI/Redocly、四语言 SDK、N−1、供应链与 Operator；OrbStack
  镜像 `browser-node:recording-privacy-v2` 从最终源码构建成功，镜像内自检返回
  `ready=true` 和 `tesseract-opencv-pii-face-qr-v2`。
- AWS TLS feature 收敛后的 `make test-object-storage` 通过，包含 archive 超时/重试、
  commit-marker-last、历史非版本 Bucket 兼容与 COMPLIANCE WORM 提前删除拒绝。

## 剩余边界

- 通用分类器存在误报/漏报；生产准入仍需客户页面与地区数据集 Replay、租户自定义选择器/视觉
  模型版本、误报预算和人工抽检。
- 目标云原生 Legal Hold、目标账户 Object Lock Apply/IAM 仍是部署环境 Gate。
- OCR 会降低可录制帧率；当前以 CDP ACK 背压保证隐私优先，不以丢弃未扫描帧换取高 FPS。
