# 真实 Chrome 录制扫描与原生保全端到端 Gate

> 日期：2026-10-29
> 基线：8b27080eccc2dc4e8121f3f48134b3eddf10fd17
> 状态：本机真实Chrome/扫描器/Recorder/Helper/MinIO链通过；PG准入与默认启用仍缺。

## 新增可重复入口

make test-real-held-recording 显式启用 RECORDING_HELD_E2E=true 和 legal-hold专用
Gate。先编译当前Storage Helper，再构建专属隐私扫描镜像；镜像构建执行12项隐私回归
（含真实分类器和编码后残留拒绝），运行时额外执行生产扫描器self-test。未使用协议透传夹具。

新ignored Rust用例使用新建loopback页面和独立Chrome Profile，关闭该专属浏览器的
其他Page，明确只验证单Page。页面有密码字段和可见测试邮箱以及持续画面变化。
真实Recorder进行语义遮罩，再通过容器中的生产Tesseract/OpenCV脚本逐帧扫描/遮罩/
复检；容器network=none、只读根、受限tmpfs、非root用户，无对象存储凭据或宿主挂载。
包装器使用本次镜像精确ID且pull=never，支持Scanner清空环境后的独立启动。

真实Helper以Unix Socket/OS UID接收段，通过原生ON首次PUT写入MinIO，随后保存
Journal与ACK。测试在采集任务仍运行时读取S3版本并逐一检查原生Hold=ON，确认这
不是仅在停止后补加Hold。显式停止后经过双任务join、准备与封口，独立oracle核验：

- 版本数精确等于2×segment_count+1，无Delete Marker，所有数据/Marker/Manifest
  精确版本均原生ON；观察采集中集合时不存在最终Manifest。
- 逐段读取每张持久化帧，重算最终JPEG Hash并匹配证明，v2/VERIFIED及OCR/视觉
  residual均为0；累计帧数精确匹配Summary，真实OCR检测计数大于0。
- 本地不可变封口Journal存在后才确认Summary；受控Helper/Chrome最终退出。

默认原有Object Storage Gate保持原分支，只有新显式目标运行E2E。CI新增独立步骤
检测真实Chrome可执行文件并运行该目标，缺浏览器或任何链失败均不回退模拟；新步骤
最多5分钟，最终Linux证据须读本提交CI。本机允许覆写隐私测试基础镜像以复用已缓存
Ubuntu24.04，CI默认仍为Debian bookworm；生产扫描算法/版本常量和分类器测试未改变。

## 实际证据

OrbStack Running / context orbstack / OS=OrbStack 后，首轮完成真实扫描镜像构建，
12项隐私回归通过；既有14项S3库+4项真实IPC=18项通过，新E2E 1项通过：
frames=18、segments=2、versions=5、ocr_detections=18。

随后将凭据限制改为不打印输入值的断言，并增加显式采集任务仍运行断言，最终源码
完整重跑18项+新E2E1项通过：**frames=24、segments=2、versions=5、ocr_detections=24**。
第二轮复用首次真实扫描镜像构建缓存，同时再次运行self-test和每帧真实扫描；帧数
随时序变化，断言按实际Summary精确核对，没有固定成首轮18帧。首次编译因include
夹具相对路径多一级失败，已修正；失败编译不计为运行证据。

常规Recorder19项通过、2项ignored；其中本轮新增E2E已显式执行，另一个既有真实
截图用例未在本轮重复（基线progress306已单独执行）。全Workspace/全部targets严格
Clippy、格式/diff、Shell语法、工作流YAML语法及文档检查通过。

日志 /tmp/agentbrowser-real-held-e2e.log、/tmp/agentbrowser-real-held-e2e-final.log、
/tmp/agentbrowser-real-held-e2e-clippy.log、/tmp/agentbrowser-real-held-e2e-unit.log。

## CI、资源和边界

基线8b27080主CI40817340480和Desktop40817340494精确SHA全成功，含Operator/
Windows/macOS。Verify日志确认生产者屏障、writer panic清理、ACK失败保留以及
完整链原生UNKNOWN拒绝实际执行。progress306已收尾，新提交仍须自己的新CI步骤。

本轮专属扫描容器/镜像Tag、Wrapper目录由Gate自动清理；测试Chrome/Helper/运行目录
由受控生命周期清理。确认本轮构建digest和无容器引用后删除两个MinIO/mc测试镜像。
专用MinIO容器/网络、扫描容器/镜像Tag及ab-rhe运行目录均0，其余8个容器及共用
基础镜像/缓存保留，未做全局prune。见/tmp/agentbrowser-real-held-e2e-cleanup.json。

本轮从Rust内部构造Owned Scope，未经过PG准入、控制面mTLS派发或真实组织授权；
默认Node仍None。Chrome在macOS、扫描器在本机Linux容器，不是目标Linux整机/
多Node/客户数据证据。E2E页面验证测试邮箱/密码区域，未宣称覆盖所有客户PII类别；
面部/二维码夹具由扫描器回归中的真实分类器用例验证，客户/侧脸/证件/医学Replay仍缺。

仍缺PG活动录制与权威准入、正式Node启用、恢复调度/预算、Seal→Hold显式安全交接、
旧非感知写入器部署/Placement围栏、跨Node、目标云KMS/IAM/复制及生产运营Gate。
原十一项目标未整体完成。许可证分发选择已在本线程向权利人询问（MIT需版权名或保留
专有），尚未收到决定，未自行更改许可证。其余真实外部系统/客户Replay/组织Gate保留。
