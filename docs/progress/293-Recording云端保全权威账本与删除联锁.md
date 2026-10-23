# Recording 云端保全权威账本与删除联锁

> 日期：2026-10-23
> 基线：86e4d25691d896df04f6aad99ff0b3f98939247b
> 状态：数据库队列/权威状态与策略触发器已实现；Worker/API 产品链仍待接入。

## V135 实现

- Additive `cloud_hold_required` 默认 false，且只允许用于 Recording；不把历史逻辑 Hold
  回写为云保护，也不生成历史成功证明。现有 API 尚不暴露此字段，当前不得通过直接
  修改数据库把它当作已可用产品启用。
- 新增 authority、不可变请求 job、租户计数 rollup。业务 authority_epoch 与恢复
  lease_epoch 分开；前序 Proof、Manifest、Node/Profile/Session/Tenant、模式和执行预算
  一经排队不得改写；同一 Recording 最多一条未 COMMITTED 请求。未知请求不能删除或
  退回 QUEUED，更不能用新 ON/OFF 覆盖。COMMITTED 必须从 EXECUTING 转入并带非空
  Hash、计数与时间；执行/未知/已提交状态必须有正租约、尝试与开始时间。
  已提交请求不能改写。
- 策略双向切换递增 desired_epoch，活动旧请求保持原样。旧请求经精确回执核对后，
  下一请求才可使用递增 Epoch 与前序 Proof 排队；初次不会排无前序证明的 OFF。
- PENDING/UNKNOWN/VERIFIED_ON/VERIFIED_OFF/DELETED 与已应用证明分别保存。有效 OFF
  必须同时满足当前 desired/applied Epoch、模式、无活动请求及 VERIFIED_OFF；缺失的
  applied 信息明确视为未释放，不能依赖 SQL NULL 的三值比较放行。
  authority 的已应用证明必须精确来自旧活动 job 的 COMMITTED 结果；不能清空未知
  活动请求、伪写 applied Proof 或在对象未删除时把状态改为 DELETED。
- Recording 的 BEFORE UPDATE 保护早于 V134 删除 guard。旧代码写 legal_hold=false
  仍会保持保全；未释放时不能产生删除授权。策略关闭 cloud 标记、删除策略与更改
  保全策略身份也受保护。旧 UPSERT 省略新字段时允许走实际冲突 UPDATE 路径，其
  默认 false 不会误拒绝，也不能将已有 cloud 标记降级。
- 录制迟到时在同一事务生成状态/请求。沿用 V134 全局策略锁，策略先按顺序锁住全部
  当前租户 Recording，再变更 authority/job/rollup，避免批量持有 rollup 再等待下一
  Recording 的逆序。后续 Worker 必须遵守 Record→Authority→Job→Rollup 顺序。
- Rollup 按状态变化维护，不需在每次查看时扫描所有 Recording；继续发既有 payload-free
  Enterprise RETENTION SSE 变化提示。真实删除投影后移除有效保全计数，保留证明账本。

## 验证

新增 `make test-recording-cloud-hold-order`，以随机密码、仅 Docker exec 可达、无网络
的独立 PostgreSQL 17 容器执行实际 V99/V128/V134/V135 和所需基础表，完整事务执行
迁移，不用字符串假装数据库检查。临时容器/凭据文件只属于本测试，结束清理。

**8 tests / 0 failures / 0 skips**：

1. 旧逻辑策略不生成云证明，重复相同策略不重复排队。
2. 未知请求的身份/预算不可改写、不可删除，旧直接释放/删除授权/降级配置均拒绝。
3. ON→OFF 使用前序 Proof，只有当前 OFF 回执完成才释放；物理删除投影移除 rollup。
4. 迟到录制与其他数据类隔离，RETENTION 最小事件与计数正确。
5. 两个真实连接交错：ingest 持有共享策略锁，策略写等待其提交后覆盖迟到记录。
6. 旧 UPSERT 省略新字段仍可变更期望状态，但不能降级已启用的云保护。
7. 未知 OFF 期间的新 ON 不覆盖旧请求；旧 OFF 精确核对后才排下一 Epoch 的 ON。
8. 缺证明不能提交或擦除请求。

数据库用例中的回执为明确的合成 SQL 夹具，验证事务约束与排队逻辑，不代表实际
对象存储副作用；真实执行器/IPC/双向 TLS 证据分别见 287、290—292。专用 Gate 强制
至少八项且不允许 skip，并加入 CI 原 PostgreSQL 步骤。普通 Replay 为
`Ran 51 tests; OK (skipped=1)`，新增环境类默认不启动 Docker，其八项由专用 Gate 运行。
兼容 Gate、Python 编译与文档检查通过。

私有证据：`/tmp/agentbrowser-cloud-hold-sql-v7.log`、`.exit`、
`/tmp/agentbrowser-cloud-hold-replay.log`、`/tmp/agentbrowser-cloud-hold-compatibility.json`。

## 发布/回退方案

- PostgreSQL 17 为当前目标。迁移仅向旧表增加常量默认列，在新空表建索引和约束，不
  扫描回写历史 Recording；lock_timeout=5s，超时让完整 Flyway 事务回滚，择时重试。
- 正式部署前核对目标表规模、写入速率、锁等待和复制延迟；本机小型 Gate 不能证明
  生产规模锁预算。启用前另核对存量 Recording 的合法 Manifest/Segment 范围、Session
  Tenant/Profile 完整性和 V134 不可逆删除授权，不能跳过不合法对象后宣称全量受保护。
- 应用可回退到旧版本，保留新增表与触发器；旧写入受到数据库保护。不得用 DROP 表、
  删除 UNKNOWN job、清空 applied Proof 或强制 false 标记来完成回退。尚未启用时保持
  默认 false；未来启用后只有核对全部当前 OFF 回执且没有未知请求才允许关闭配置。
- 上线核对 authority 活动请求与同域 job 的关联、未完成请求唯一性、rollup 与各状态
  COUNT 的一致性，以及无当前 OFF 证明的 Recording 始终 legal_hold=true。

## 仍须完成

Worker 两阶段提交/领取/有限核对、崩溃与旧租约 fencing、审计、正式 API/四语言 SDK/UI
与权限、完整控制面→真实 Rust Node→MinIO mTLS 验收和规模测试。SQL 账本不是这些
能力的替代品；当前没有调度器从新队列派发请求。目标云 IAM/Object Lock/Legal Hold、
跨 Region、客户系统/数据、组织与许可证 Gate 继续保留。

aa2fdcc 主 CI **39906058046**、Desktop **39906058041** 已读取最终精确 SHA 与全部
Job 成功，包含 Integration/完整 GameDay；进度 291 已收尾。Gateway 86e4d25 的主 CI **39907385865**、Desktop **39907385833** 也已读取
最终精确 SHA 与全部 Job 成功，含五项 Java mTLS 回归、Integration/完整 GameDay；
进度 292 已收尾。本次 V135 仍须验证自己的 CI。

新增 Gate 曾在 PostgreSQL 初始化临时 Unix Socket 服务停止时连续两个 setUp 连接失败，
发生在迁移和业务断言前；ready 检查现固定 TCP 127.0.0.1，等待最终服务器。修复后
实际八项 Gate 再次全部通过；没有把该夹具故障归因于业务 SQL。
