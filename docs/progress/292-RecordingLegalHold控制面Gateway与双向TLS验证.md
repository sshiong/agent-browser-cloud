# Recording Legal Hold 控制面 Gateway 与双向 TLS 验证

> 日期：2026-10-23
> 基线：aa2fdcc2cb355d14ee20f8a925f7f9f7ad01586d
> 状态：控制面 Gateway 已实现；权威队列和策略产品链仍未接入。

## 实现

- 新增 `RecordingLegalHoldNodeGateway` 与正式 Spring 实现，复用生产强制双向 TLS 的
  `GrpcTransportFactory`，调用既有 additive `ApplyRecordingLegalHold`。
- 请求明确绑定操作、业务 Epoch、Node、Tenant/Profile/Session/Recording、精确 Manifest
  Hash/字节/Segment 数、ON/OFF、前序 Proof 和 1—120 秒预算。请求越界、缺能力、无
  可用 Node 或心跳超过 30 秒时，在此尝试调用 RPC 前拒绝。
- 仅接受 `recordingLegalHold=versioned-cas-journal-v1`。RPC deadline 为不可变执行预算
  加五秒；Gateway 不自行重试，后续队列必须保存原请求以核对未知结果。
- 回执重新校验操作、Epoch、Node、Recording、模式、两种 Hash、完整版本计数上下界和
  有界完成时间。只返回安全元数据，不返回对象 Key、Version ID、URL 或凭据。
- 请求前拒绝只说明本次未派发，不能消除前次不确定性。派发后的传输错误、服务端拒绝
  或无效回执均归为 UNKNOWN，不推断对象未修改或 OFF 已完成。异常不保留服务端描述
  或原始 Cause，避免将底层对象/凭据细节传出。
- 没有公开新 REST API，没有启动生产者，不改变现有策略保存结果或删除权限。

## 可重复验证

OrbStack 中使用现有 `eclipse-temurin:21-jdk`，仓库只读挂载，构建目录独立位于 /tmp。
全新 Linux Gradle 环境实际执行 `generateProto`、`compileJava`、`compileTestJava` 和
目标测试，**5 passed / 0 failed / 0 errors / 0 skipped**，没有跳过生成或复用旧 Java
Protobuf 声称新方法已编译。

1. 临时自建 CA 签发服务端/客户端证书；真实 loopback Netty 服务强制客户端证书，
   Gateway 使用正式 production TLS 工厂完成 ON/OFF，校验全部请求范围、前序 Proof
   和历史版本计数。
2. 十二类无效回执（身份/Epoch/模式/Hash/计数/时间）经真实双向 TLS 返回，全部拒绝
   并保留未知结果。
3. 服务端 FAILED_PRECONDITION 含模拟私有描述，仅返回固定 UNKNOWN，Cause 为空，
   服务端计数恰好一次。
4. 未受信任的客户端证书被握手拒绝，服务端方法执行计数为零；Gateway 仍保守报告未知。
5. 无前序 Proof 的 OFF、无能力、无 Node 与过期心跳在创建 Channel 前拒绝。

私有证据：`/tmp/agentbrowser-hold-gateway-java.log`、对应 `.exit`，独立构建路径记录在
`/tmp/agentbrowser-hold-gateway-java-build.path`；其中 JUnit XML 给出精确用例结果。
Java 格式与文档检查通过；本提交完整 CI 仍须收尾。

## 剩余范围

这里的真实 mTLS 服务端是契约测试实现，不是 Rust Node 和 MinIO 的完整跨进程产品链。
后者仍须在控制面 PostgreSQL 权威队列、不可变请求/租约/审计、PENDING/UNKNOWN/
VERIFIED 投影、策略 API/四语言 SDK/UI、删除与迟到录制联锁接入后验收。远程 PREPARED
未知 Intent 不能被新 ON/OFF 覆盖；恢复不能伪造回执或释放保全。
目标云账户/IAM、客户数据集、真实供应商与生产/组织/许可证 Gate 继续保留。
