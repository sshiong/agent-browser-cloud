# Recording 二维码几何检测与真实扫描流协议修复

> 日期：2026-10-07
> 基线：a6a893988cfb5fc5a2a447dae0e09614725d82c4
> 范围：用户目标 5 的全帧隐私扫描；真实生产 Python 路径及 Debian 进程回归。

## 复核与真实故障

原始十一项的通用录制、Profile、Proxy 与环境管理代码已有对应实现；真实客户/供应商/
目标云和许可证 Gate 仍需输入。progress 280 原生属性候选仍未接入生产，继续扩展该
实验不能直接计作原始十一项的自动化覆盖率提升。本次回到实际录制路径，找到可在
仓库内修复的故障。

扫描器此前调用 QRCodeDetector.detectAndDecodeMulti/detectAndDecode，隐私遮罩只使用
返回的角点，丢弃解码正文。自有两枚合成二维码在 Debian OpenCV 上可以定位，但当前
构建没有 QUIRC 解码支持，原生库向 **stdout** 写入额外诊断行。

最初假设损坏多码会漏定位；实际实验没有支持该假设：未损坏及中部损坏 4×4/7×7 码格
均定位到两枚码，旧解码接口也保留两个几何区域。损坏 10×10/14×14 码格后两种接口均无法定位。
因此没有将这次改动写成新增视觉类别或提高损坏 QR 检出率。

真正的故障是 NDJSON 污染：真实旧扫描进程输入两帧，exit 0，返回两个 JSON 证明，
另有四行非 JSON stdout；stderr 没有这些诊断。Rust RecordingPrivacyScanner 逐行读取
一条 JSON 作为下一帧证明，不接受库诊断。独立存活流复验取得 **0 个合法响应之前就
遇到非 JSON**，当时扫描进程仍活着；不是只在退出收尾时才影响通道。

新增实际 Debian 子进程回归在旧源码失败：

`6 != 2 : scanner must emit exactly one NDJSON response per frame`

它通过 subprocess 调用真实扫描脚本，不 mock OCR/二维码、stdout 或 JPEG 编码，也
不把 C 库输出当成可跳过的噪声。测试只使用自有合成图片与 marker，没有客户数据。

## 生产修复

- 用 detectMulti/detect 定位几何区域，直接复用现有角点到有界遮罩的映射。
- 不调用二维码解码 API，不读取其载荷；无需 QUIRC，避免这条原生诊断输出路径。
- 保留全帧 OCR/PII、Face、QR 遮罩、最终编码后的 JPEG 再扫描与 SHA-256 证明。
- 保留分类异常、区域预算、零残留、Rust 逐行证明验证和原始帧不入队的 fail-closed。
- scanVersion、redactionPolicyVersion、Node 能力、API/RPC、数据库及 SDK 格式保持兼容；
  没有新增分类器或视觉覆盖承诺，不需要给相同 v2 证明格式创建新版本。

OpenCV 官方区分[几何定位与解码 API](https://docs.opencv.org/4.x/de/dc3/classcv_1_1QRCodeDetector.html)：
detect/detectMulti 返回二维码四边形；本次使用定位结果，没有执行载荷解码。

## 匹配验证

OrbStack Gate 前核对 **Running / orbstack / OS=OrbStack**。用 Browser Node 相同 Debian
OCR/OpenCV 依赖构建测试镜像；测试步骤 **network=none / 非 root**。

- `make test-recording-privacy`：**6 项通过**。新增真实进程回归每帧恰好一行 JSON，
  两枚中部损坏二维码均检出并遮罩；两个证明的 OCR/视觉残留均零，最终 JPEG 已无 QR
  几何区域，Hash 与返回字节一致。原真实 OCR/QR 自检和最终像素证明回归保留。
- Rust `session-recorder` 与 `storage-helper`：**43 passed / 0 failed / 3 ignored**，
  验证消费端、失败帧不入队、最终像素 Hash 与历史证明兼容。
- Python 编译、diff 检查及 docs-check（七项文档测试、中英文目录清单/本地链接）通过。
- 原生属性候选没有新增改动；其回调保护、全部 Intrinsic/Context 连续登记/Scope/
  重连/回收，以及完整区域授权/动作/独立 Outcome 仍保留。

私有日志：

- `/tmp/agentbrowser-qr-multi-geometry-probe.log`
- `/tmp/agentbrowser-qr-scanner-stream-old-probe.log`
- `/tmp/agentbrowser-qr-scanner-old-live-stream.log`
- `/tmp/agentbrowser-recording-qr-geometry-old-regression.log`
- `/tmp/agentbrowser-recording-qr-geometry-privacy.log`
- `/tmp/agentbrowser-recording-qr-geometry-rust.log`

基线 a6a8939 主 CI **37611126559** 与 Desktop **37611126462** 最终 exact SHA 已核验，
均 completed/success；Integration、Object Storage GameDay、Operator、Windows/macOS
全 success。这不替代本次提交自己的 CI。

## 未完成项

不追溯将本故障归因于旧公开网站失败。此修复不是客户视觉集/侧脸/证件/医学影像验收，
也不完成目标账户 Object Lock/IAM 与云原生 Legal Hold。真实 OTP/MFA/支付、客户 SPA、
目标 Linux/云/多 Region/硬件长稳、组织发布与权利人许可证决定仍缺；十一项目标继续
保持 active，不以本机真实进程和绿色 CI 冒充 V16 生产验收。
