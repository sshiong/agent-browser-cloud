# Recording v2 权威投影与真实 PG 迁移验证

> 日期：2026-10-29
> 基线：7172b2733898e3e9329c4f8e8b22b1237a3a866f
> 状态：真实 PostgreSQL 投影与迁移 Gate、完整 Integration 均通过。

## 代码复核发现

真实 Recorder 已生成 redaction_policy_version=2，Java NodeEventMapper 和 V099 数据库
约束却只接受1。此前真实 Chrome/扫描器/MinIO 验证覆盖采集与存储，不足以证明控制面
能够接受最终事件；SQL 注入的播放夹具也不能证明生产 JDBC 投影可用。

新增真实 PG 测试首次执行还发现 production JDBC SQL 的时间参数在与 make_interval
相加时被推断为 interval，导致 retention_until 类型错误。现在显式 CAST 为 timestamptz，
仍以结束时间加租户策略或默认保存天数计算期限，不改变原有策略选择。

NodeEventMapper 现在精确接受1/2并原样保存版本，拒绝0、3和uint32最大值。
API 已支持整数版本，无正式 API/Protobuf 字段变化，255 Operations / 358 Schemas 不变。
v2 元数据接受不等于控制面独立验证像素；逐帧扫描与上传证明仍由既有 Node/Helper 校验。

## 迁移计划、风险与恢复

V137 在一个短事务中替换命名 CHECK 为 IN(1,2)，保留 Hash、字节数、时间与保存期
全部约束，使用 NOT VALID 避免在 ACCESS EXCLUSIVE 锁下扫描全表。lock_timeout=5s，
无法及时取得锁则整笔回滚，不丢失旧约束。不重写任何历史行，不把 v1 标记成 v2。

V138 是独立 Flyway 迁移事务，以 SHARE UPDATE EXCLUSIVE 锁验证已有行；普通录制
INSERT/UPDATE 可并行。lock_timeout=5s、statement_timeout=5min 限定等待和扫描，
仓库未配置 Flyway group=true；部署必须保持两迁移分别提交。

若 V138 失败，V137 已提交的 NOT VALID CHECK 仍约束新写入；启动失败须排查数据/
容量/锁等待后重试，不跳过验证。此处只有小规模专用 PG17 证据，没有生产表规模、
复制延迟或5分钟容量证书，真实发布须先统计表大小/写入率及验证耗时。
回退应用不会删除 v2 行，但旧 mapper 不能处理新 v2 事件，应优先前向修复并按原有
Node Journal/Inbox 恢复规则核对；不要在已有 v2 行时恢复 v1-only CHECK，也不直接
改写迁移历史、删除记录或声称已自动补齐旧事件。

## 实际测试

make test-recording-retention-order 在 OrbStack 专用 loopback PG17 上通过30项，
failures/errors/skipped均0：删除顺序14、Legal Hold13、新投影迁移3。
新3项经实际 Protobuf→NodeEventMapper→生产 SessionRecordingApplicationService→JDBC
写入 PG，只有 SessionRepository 的查询身份使用 mock：

- V137 前拒绝 v2；V137 后 v1/v2 共存，重放幂等，V138 后 convalidated=true。
  比较整行 Hash 证明旧 v1 未改写，列表保留精确版本且不含私有对象坐标。
- 0/3、非法 Hash、零字节、倒置时间、过短保存期均拒绝，拒绝后原行保持不变。
- 验证事务完成扫描但保留锁期间，另一连接以250ms锁超时成功执行生产录制 INSERT，
  证明验证锁与普通写入兼容；这不是大型表扫描性能测试。

Java 常规测试686项：656通过、30项专用PG条件跳过；上述 Gate 单独执行全部30项。
spotlessCheck通过。首次新增PG测试3项失败捕获真实SQL类型错误，修复后完整30项通过，
失败日志保留，不计为成功证据。

完整 make test-integration 退出0，真实 Flyway 全迁移启动通过，最终核验
recording_playback_access、retention_physical_deletion、retention_versioned_physical_deletion、
cloud_hold_native_mtls、cloud_hold_native_rejection_preserves_unknown、frame_redaction均true。
该集成的初始录制对象/元数据仍为夹具、CDP/Scanner为协议夹具；不能据此声称新增
真实Chrome→mTLS事件入库证据。当前提交 CI 仍待精确 SHA 核验。

基线7172b27主CI40820328460、Desktop40820328447全成功；新增Linux真实Chrome
保全用例实际执行通过（22帧/2段/5原生ON版本、OCR22次）。

## 保留边界与资源

这次证明事件映射、生产 JDBC 与数据库约束闭环，不是 Node mTLS 录制事件采集全链证书。
PG 上传准入、默认 Node upload_hold=None、Seal→Hold 所有权交接、旧写入器资格围栏、
跨 Node 与目标云仍未关闭；原十一项中外部/环境/组织及许可证决策 Gate 继续保留。
许可证问题已发给权利人，未回复前不选择许可证。

专用 PG runner 使用随机名称/凭据和退出清理；完整 Integration 结束后核对本轮容器0、
本轮网络0。仅移除构建日志中可证明镜像ID且无容器引用的MinIO/mc两个测试镜像。
原8容器、早于本轮的1个空网络、用户文件与共享缓存保留。清理报告保存在
/tmp/agentbrowser-recording-v2-cleanup.json；没有执行全局prune。
