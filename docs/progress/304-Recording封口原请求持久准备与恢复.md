# Recording 封口原请求持久准备与恢复

> 日期：2026-10-29
> 基线：242bc32f235a35cf52183bf1c76225c91b36b32a
> 状态：本地准备/恢复组件与真实 Helper/MinIO 链通过；正式 Recorder/控制面编排仍缺。

新增 recording_upload_seal_requests，仅扩展 SQLite 表和触发器，不改写旧回执。
FULL synchronous / IMMEDIATE 事务先核对完整连续段集合、Scope/Archive Hash、计数
和时间范围，再保存精确 Scope、Finalization、expected_scope_sha256。相同请求幂等，
改变时间范围/计数/身份也不能覆盖原请求；无过期清空、删除或新 Epoch 替换路径。

准备事务与段登记共用 SQLite 写锁，数据库触发器在准备成功后拒绝新段，精确旧段
重放仍允许。响应落盘路径若发现准备请求，必须逐字段匹配原请求才接受封口证据。
既有未准备的封口证据入口保留组件兼容性，不能宣称系统强制所有历史调用先准备。
读取接口供调用者按原请求恢复；未增加自动扫描/调度器或启用默认录制路径。

段集合核验提取为共同函数，准备与结果落盘使用同一规则。准备记录不包含像素、
对象路径或URL；本地数据库/管理员仍属可信边界，Hash不是云签名。不能将准备、
回执存在或本地禁止新增登记当成采集生产者已停止、远端全局静默或可放行OFF的证明。

## 验证与资源

- Journal16项通过，新2项验证关闭重开后原请求保持、无结果时原请求仍保留、请求
  不能改变、晚到段拒绝/旧段可重放、结果必须匹配准备、SQL不能UPDATE/DELETE，
  以及两个独立Journal竞争准备/追加时唯一顺序。
- OrbStack Running / context orbstack / OS=OrbStack 后，完整专用Gate
  **14项库 + 4项真实IPC = 18 passed / 0 failed / 0 skipped**。合成录制夹具先
  持久准备、关闭重开Journal，再读取原请求调用真实Helper→MinIO，最后精确封口
  证明落盘恢复与晚到段拒绝均通过，独立oracle确认3版本原生ON。
- 全Workspace/全部targets严格Clippy、格式/diff和文档检查通过。

日志 /tmp/agentbrowser-seal-request-unit.log、
/tmp/agentbrowser-seal-request-minio.log、/tmp/agentbrowser-seal-request-clippy.log。
“结果未知”本地场景由准备后无结果重建，并非实际kill进程/故意丢失云响应；真实链
只证明本机原请求恢复，不是跨Node或客户录制证据。

退出后专用容器/网络为0，确认本次构建digest归属及无容器引用后，删除两个MinIO/mc
测试镜像；其余8个容器、用户文件、基础镜像和共享缓存保留。无全局prune；清理记录
/tmp/agentbrowser-seal-request-cleanup.json。

基线242bc32主CI40719477515、Desktop40719477473精确SHA均成功。本提交cacd11d
主CI40721913323在Integration的假成功Outcome完成请求因40P01事务冲突503失败，
Operator成功；Desktop40721913209中Windows成功、macOS取消，不能记为全通过。
后续有界契约重试与失败续行验证见progress305，新提交须核验自己的CI。

正式Recorder/Node调用、PG活动录制与权威准入、持久恢复调度/重试预算、生产者停止
并await、跨Node恢复、Seal到Hold显式安全承接、旧非感知写入器部署资格/Placement
围栏及目标云Gate仍缺。原十一项目标其他外部集成、客户Replay、Linux/Region/组织
和许可证Gate全部保留，目标仍未完成，不满足V16生产发布条件。
