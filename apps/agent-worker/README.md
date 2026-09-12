# Agent Worker

`agent-worker/v1` 是独立于 Control Plane 的最小权限调度故障域。它只接收不透明的
`jobId/taskId`、一次性 Claim Token、Epoch 和 Lease，不接收 Prompt、Plan、网页数据、
Capability Token、客户凭据或任意 Runner 命令。

Worker 仅能调用五个固定接口：Claim、Start、Heartbeat、Drive、Fail。真正的 Tool
执行仍由 Control Plane 的 Capability/Operation/Outbox 安全内核完成。生产部署必须使用
HTTPS/OIDC、独立 ServiceAccount、只读根文件系统、无宿主挂载，并只允许访问 DNS 和
Control Plane。

`vision_worker.py` 只处理精确 Browser State、Target Revision、活动标签页和有界
`CHALLENGE_REGION` 绑定的 JPEG。它先在本地以 Tesseract（英文/简体中文）产生坐标化 OCR，
使用固定 ImageMagick 命令在内存中遮盖命中 PII 的整行区域，再执行第二次 OCR；仅当残余敏感
信号为零时才把脱敏后的 JPEG 交给外部视觉模型。OCR 正文、原始像素和对象路径不会提交给
Control Plane，控制面只接受固定扫描器版本、OCR 哈希、检测/遮盖计数和零残余证明。无法取得
可信区域、状态已变化、OCR/遮盖失败或二次检测仍命中时均 fail-closed 并进入人工兜底。
