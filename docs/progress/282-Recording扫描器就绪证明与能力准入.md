# Recording 扫描器就绪证明与能力准入

> 日期：2026-10-09
> 基线：837fbbfa1aa5313e0a05d2b094a2856733f06f92
> 范围：原十一项中的 Recording 全帧隐私能力准入；生产 Node 入口与真实子进程验证。

## 实际缺口与旧代码回归

progress 281 修复了真实 Debian 扫描器 QR 解码诊断污染 stdout。进一步核对 Node 入口：
配置读取阶段执行扫描器 --self-test，但仅检查 process exit success；没有读取 ready
或 scanVersion，也没有拒绝非 JSON stdout。独立真实扫描自检中，旧脚本退出 0 而 stdout
有两行、不是完整 JSON；修复后的真实脚本仍退出 0，但只产生一行有效 v2 响应。

本次先把原入口判断原样提取到生产使用的私有函数，保留其进程参数、隔离环境和行为。
新增真实子进程回归：自有脚本返回有效 JSON 后再输出固定诊断，exit 0，预期不能声明
扫描器能力。旧逻辑在 case 0 返回 true、预期 false，**0 passed / 1 failed / exit 101**。
不是仅针对一个未接入生产的解析 helper 写测试。

## 实现

生产准入函数同时要求：

1. scanner path 为绝对路径，进程执行成功且 exit success。
2. 自检 stdout 长度最多 **512 字节**；这是响应准入上限，不是子进程读取内存预算。
3. 对完整 stdout 解码为带 deny_unknown_fields 的 typed JSON，不能只读取有效前缀。
4. ready 为布尔 true；scanVersion 精确匹配 `tesseract-opencv-pii-face-qr-v2`。

额外诊断、重复字段、额外字段、拼接对象、缺字段、错误类型/版本、非零退出和缺失
可执行路径均返回 unavailable；响应字节和解析错误不进入公共 API、Node label 或日志。
失败判定继续参与原 `recordingRedaction=full-frame-privacy-v2` 能力声明。有效 v2 自检
响应的原格式保持，API/Protobuf/数据库/SDK 与能力名称没有变化；没有改变启动进程的
同步执行方式，也没有宣称增加启动取消或超时能力。

## 验证

- 一个 Unix 真实子进程回归执行 **12 个输出/退出场景**：诊断污染、空输出、空对象、
  ready=false、字符串 ready、错误版本、重复字段、额外字段、拼接对象、超长响应、
  非零退出、正常 v2。另验证相对路径和缺失文件拒绝。自有临时目录/脚本均为 0700，
  随机名称且只清理自己的目录；不修改环境变量或依赖外部账号。
- `cargo test -p node-agent`：**33 passed / 0 failed / 1 ignored**。
- Rust Workspace：**241 passed / 0 failed / 10 ignored**。
- Rust 1.99 `node-agent --all-targets` 严格 Clippy 通过。
- Java `AgentBrowserActionApplicationServiceTest`：**9 passed / 0 failed / 0 skipped**。
- `make test-recording-privacy` Gate 通过，扫描器/测试源未变化，复用已验证的六项 Debian
  OCR/OpenCV 镜像构建缓存。生产脚本与 Host Integration 夹具都返回相同有效 v2 字段。

### 本机 Integration 复验过程

前两轮完整集成在高层 `act` 的 HOVER Task 创建 POST 返回 HTTP 409、exit 22；未取得
结构化错误原因，不能认定为 `STATE_CURSOR_STALE`。第三轮只把该 POST 响应保存到本轮
私有临时目录，并输出固定布尔诊断；成功后继续原有 Task COMPLETED 断言。此轮高层
act/wait/handoff、JavaScript Evaluate、Screenshot 和文件操作均已通过，409 没有复现。
没有改动正式 smoke 源码、生产围栏、动作断言、超时预算或增加动作重放。
第三轮随后完成全部集成：**exit 0、audit_chain_valid=true**，含录制播放授权、版本对象
物理删除、Profile 应用感知恢复、Coordinator 切换和 Challenge 检查。前两轮失败仍保留，
不把一次完整通过写成连续长稳或追溯其错误原因。

源码和现有 `AgentBrowserActionApplicationServiceTest` 同时确认游标拒绝发生在
tasks.create 之前，且不会调用执行/Reviewer/Worker/路由；这证明精确拒绝的顺序，不是
那两次 409 的原因证明。本机脚本仅固定仓库根路径，并沿用 macOS 已生成 Protobuf 的
`bootJar -x generateProto` 构建方式；当前 Java/Rust 均重新构建。完整 Linux CI 仍须在
本次提交上独立核验。

私有证据：

- `/tmp/agentbrowser-recording-ready-old-regression.log`
- `/tmp/agentbrowser-recording-ready-node-tests.log`
- `/tmp/agentbrowser-recording-ready-rust.log`
- `/tmp/agentbrowser-recording-ready-clippy.log`
- `/tmp/agentbrowser-recording-ready-privacy.log`
- `/tmp/agentbrowser-recording-ready-integration.log` 与同前缀 `.exit`
- `/tmp/agentbrowser-recording-ready-diagnostic-integration.log` 与同前缀 `.exit`
- `/tmp/agentbrowser-recording-ready-reason-integration.log` 与同前缀 `.exit`
- `/tmp/agentbrowser-recording-ready-action-service.log` 与同前缀 `.exit`

基线 837fbbf 主 CI **37615377020** 与 Desktop **37615376959** 最终 exact SHA JSON
已重新读取，completed/success；Integration smoke test、Object Storage checkpoint and
recording GameDay、Operator、Windows/macOS 全 success。这不替代本次提交自己的 CI。

## 剩余范围

此处关闭能力准入的响应校验缺口，不是增加视觉类别或证明全部客户画面可检测。客户
视觉集/侧脸/证件/医学影像、目标账户 Object Lock/IAM 和云原生 Legal Hold 仍缺。原十一
项的真实 OTP/企业 MFA/支付、客户 SPA、具体跨域 Provider 协议与可信结果、商业 Proxy/
模型服务端取消回执、目标 Linux/云/多 Region/硬件长稳、组织发布与权利人许可证决定
仍需要对应证据；完整区域授权/动作/独立 Outcome 尚未完成。持续目标保持 active。
