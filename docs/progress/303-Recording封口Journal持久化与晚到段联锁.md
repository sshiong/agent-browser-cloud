# Recording 封口 Journal 持久化与晚到段联锁

> 日期：2026-10-28
> 基线：3bd317e8624f9c052ab74c283107e66c0f3b15d6
> 状态：本地恢复组件与真实 Helper/MinIO 回执落盘验收完成；正式生产调用和云保全交接未完成。

## 实现与迁移

SQLite 新增 recording_upload_seals，不修改、回填或清除旧段回执。保存 Scope、
Finalization 和 Helper 封口证明，单行上限16KiB，不保存像素、对象 Key、URL 或 Secret。
FULL synchronous + IMMEDIATE 事务下逐行核对完整连续的0..N-1段回执、逐段 Hash、
Scope/Archive Hash、计数及时间范围，再不可变提交精确封口证据；重复相同证据幂等，
冲突证据不能覆盖。新增按 Tenant/Session/Recording 精确读取供后续恢复调用。

封口与段登记共用 SQLite 写事务锁；封口先成功则新段失败，新段先成功则旧计数不能
封口。数据库 BEFORE INSERT 触发器同时拒绝旧代码/原始 SQL 新增晚到段，已存在段
的精确重放仍允许。封口表禁止 UPDATE/DELETE。DB 文件/管理员本身仍属可信边界，
本地证据不是云签名、采集生产者停止证明或控制面授权。

只增加表和触发器，无 destructive rollback。回退应用时保留表/触发器，旧代码晚到
写入应失败，不能删掉 fence 以恢复写入；原始旧库升级、大规模迁移/长稳及部署资格
仍须正式 Gate，不能把新数据库的单测当成所有历史版本升级证书。

## 验证

- 客户端10、契约5、Journal14，共29项通过。新3项覆盖缺段/间隙、Scope/Epoch/
  协议/计数/时间冲突、关闭重开恢复、跨租户空结果、不可变 SQL、封口后旧段重放与
  新段拒绝，以及两个独立 Journal 控制器封口/追加竞争的唯一顺序。
- OrbStack Running / context orbstack / OS=OrbStack 后，完整专用 Gate最终
  **14项库 + 4项真实 Unix IPC = 18 passed / 0 failed / 0 skipped**。
  真实 Helper→MinIO 封口证明在 SQLite 提交后关闭重开，精确读取与重放一致，晚到
  段登记拒绝；独立 S3 oracle 继续核验3个版本均原生 ON。
- 全 Workspace/全部 targets 严格 Clippy、格式/diff检查通过。

私有日志：/tmp/agentbrowser-seal-journal-unit.log、
/tmp/agentbrowser-seal-journal-minio-final.log、
/tmp/agentbrowser-seal-journal-clippy-final.log。

首轮完整 Gate 旧延迟 OFF 用例失败，脱敏诊断为
RECORDING_HOLD_BUCKET_CONFIGURATION_UNAVAILABLE，代理只有读取、没有 Hold PUT。
这定位到注入前元数据阶段，而非预期的未知写入。仅该测试夹具将operation预算从0.5秒
增至5秒，注入延迟相应从1.5秒增至6.5秒；生产配置与其他用例保持原值。不重试、不
跳过或放宽 UNKNOWN 断言，仍验证唯一一次 Hold PUT 在超时后返回200、跨控制器
新ON不能覆盖原未知请求、版本原生状态精确匹配。修改后完整18项重跑成功。
本次捕获的前置超时已定位；不能据此断定此前未捕获错误的每次根因都相同。

## CI 与资源

基线3bd317e主CI **40698720830**、Desktop **40698720732** 精确SHA全成功，
含Operator、Windows/macOS；Verify日志确认新封口IPC、客户端错配矩阵及完整链
UNKNOWN拒绝标记均实际执行。progress302已收尾。本提交242bc32主CI40719477515、Desktop40719477473精确SHA全成功；
Verify日志已确认封口Journal竞争、真实IPC和原生UNKNOWN拒绝标记实际执行。

专用容器/网络均0，以本轮构建digest确认来源且无容器引用后删除MinIO/mc两个
测试镜像。保留其余8个容器、用户文件、基础镜像及共享缓存，无全局prune；见
/tmp/agentbrowser-seal-journal-cleanup.json。

## 剩余

正式Recorder/Node尚未调用这些组件；本轮未启用上传保全或改变默认录制行为。
仍缺PG活动录制与权威准入、持久封口请求/未知恢复编排、生产者停止并await、跨Node
恢复、Seal到Hold队列的显式引用及安全承接、旧非感知写入器部署/Placement围栏和
目标云验证。SQLite记录只能证明本地回执，不允许直接放行OFF。合成像素/证明夹具
不算实际OCR/客户Replay证据。原十一项目标其他外部系统、Linux/Region/组织及
许可证Gate全部保留，未通过V16生产发布。
