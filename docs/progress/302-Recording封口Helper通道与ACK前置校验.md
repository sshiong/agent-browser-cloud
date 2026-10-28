# Recording 封口 Helper 通道与 ACK 前置校验

> 日期：2026-10-28
> 基线：cce2465c84f8fa4d5d06b869180748f30be2d188
> 状态：Helper/客户端组件与真实 MinIO 链通过；正式 Recorder/PG 授权及所有权交接仍缺。

## 已实现

新增独立 SEAL_RECORDING_HELD_UPLOAD 命令，旧 Helper 不认识时失败关闭，不回退普通
CompleteRecording。共享类型保留已有 Finalization 序列化，新增显式 protocol_version=2
封口回执；内部不可变 SEALED 对象和既有 Scope 字节不变，不修改外部 API/SDK。

Helper 在同一 Profile 锁内恢复精确 Workspace，逐项读取有界、非符号链接的 ACKED
文件。只允许连续范围内的规范文件名；数量、Receipt Hash、Scope、段序号、Archive
Scope Hash 及累计帧/脱敏计数必须精确匹配。缺 ACK、仍有 ndjson/临时文件或未知目录
项时，先拒绝，再保持对象存储不变；不自动删除待处理文件以满足封口条件。

ObjectArchive 新增绑定 expected_scope_sha256 的入口，在读取不可变 AUTHORITY 后、
改变 WRITER CAS 或上传 Manifest 前核对 Hash。库测试独立确认错误 Hash 后 WRITER
字节不变，仍只有原来的4个数据/Marker版本。原无 expected 参数的组件入口保留兼容。

客户端重验协议版本、Operation/Authority/Placement、Scope Hash、Recording、完整
计数、Manifest Hash/大小、2N+1版本数、Inventory/Proof Hash 格式与时间，拒绝不相关
对象 Key；新回执不能代替段上传或 ACK 回执。内部对象 Key 不进入公共 API/审计。
这些 Hash/字段核对依赖受信 Helper，并非客户端直接向云端验证的签名。

## 可重复验证

- helper-client 10项、helper-contracts 5项通过。新响应矩阵覆盖18种情况，包括有效
  回执、旧 Helper、无 Proof、错误 Scope/Epoch/Manifest/数量/时间/Key，逐一断言
  不发生第二次连接或降级调用。
- OrbStack Running / context orbstack / OS=OrbStack 后，完整专用 Gate 最终
  **14项存储库 + 4项真实 Unix IPC = 18 passed / 0 failed / 0 skipped**。
- 新真实链先经 Node Journal 持久化及 ACK 清理，再验证残留文件、缺 ACK、错误
  Scope Hash 都不能改变远端版本集合；正确封口与重放返回相同证明。独立 S3 API
  确认3个数据/Marker/Manifest版本均为原生 ON，无 Delete Marker。
- 全 Workspace/全部 targets 严格 Clippy、格式及 diff 检查通过。

日志为 /tmp/agentbrowser-seal-ipc-unit.log、
/tmp/agentbrowser-seal-ipc-minio-diagnostic.log、
/tmp/agentbrowser-seal-ipc-clippy-final.log。

首轮新 IPC 断言误沿用旧场景“暂存文件仍存在”，已改为按场景验证，未更改产品逻辑。
下一轮既有延迟 OFF 用例返回非 UNKNOWN，故整轮失败且 IPC 未执行；补充只含固定
错误码与代理状态分类/HTTP状态码的诊断后，完整18项重跑成功。该偶发错误根因仍未
确认，没有跳过用例、放宽断言或增加生产重试，保留前一轮失败日志
/tmp/agentbrowser-seal-ipc-minio-final.log，不将分轮结果拼为一次成功。

## CI、资源与剩余边界

基线 cce2465 主 CI 40694039631、Desktop 40694039774 精确 SHA 全成功；主流水线
两个 Job 均成功，Verify 日志实际包含封口测试与 UNKNOWN 完整链拒绝标记。
progress301 已补收尾；本提交3bd317e主CI **40698720830**、Desktop **40698720732**
精确SHA全成功，Operator/Windows/macOS均成功；Verify日志已确认新封口IPC、
客户端拒绝矩阵和完整链UNKNOWN拒绝标记实际执行。

测试退出后，专用容器/网络均为0。以本次构建日志 digest 确认来源、检查无容器引用后，
删除两个 MinIO/mc 测试镜像；其余8个容器、用户文件、基础镜像和共用缓存保留，未用
全局 prune。记录 /tmp/agentbrowser-seal-ipc-cleanup.json。

本轮使用合成像素/v2证明、受控本地 Unix UID 与真实 MinIO，不是实际 Recorder/OCR
或客户数据验证。本地 ACK 完备不代表采集任务已停止；正式调用前必须停止并等待生产者，
且仍缺持久化封口/恢复状态机、PostgreSQL 活动录制与权威授权、Node/Recorder 接入、
Seal Proof 到 Hold 队列的显式承接、旧非感知写入器部署资格/Placement 围栏及跨 Node
恢复证明。Helper ACK 枚举逐文件有界，但大规模目录扫描及容量/时限仍须验收。未知封口
不能被当成成功，也不能据此放行 OFF。原十一项真实外部系统、客户 Replay、Linux/
Region/组织/许可证等范围继续保留，不满足 V16 生产发布条件。
