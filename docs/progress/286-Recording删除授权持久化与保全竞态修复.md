# Recording 删除授权持久化与保全竞态修复

> 日期：2026-10-19
> 基线：ea898047875bf6c58041402d41f876d051af5f3d
> 范围：原十一项中 Recording 物理删除与 Legal Hold 的数据库时序。

## 可重复缺陷

旧 Worker 在一个数据库事务中锁定 Recording、修改 EXECUTING、调用 Node 并提交
Receipt。事务中的行锁能排序成功响应，但不能取消已经发出的对象删除。

真实 PostgreSQL 17、实际生产 Service、实际 V099/V128 表结构下，分别注入：

1. Gateway 已接受请求但返回不可用；
2. 请求发出后抛出 Error，模拟执行事务崩溃回滚。

两种情况随后都成功接受 Legal Hold，旧测试 **2 tests / 2 failures**，均为业务
断言期望拒绝而未拒绝。这里没有真实云删除：远程故障由 Gateway 夹具注入，数据库
事务和治理路径真实执行；不把该证据表述为 AWS 保全认证。

## 实现

- V134 添加 nullable `deletion_authorized_at`，不重写历史录制、对象或 Receipt。
  不可撤销授权代表对象可能进入删除流程，不代表对象已被删除。
- Worker 第一阶段使用 REQUIRES_NEW：共享策略锁、Recording/Job 行锁、有效策略
  校验、Node 能力校验、授权与 EXECUTING/Epoch 提交。第二阶段才调用 Node，并用
  精确 Job/Epoch 和 Recording 行锁提交真实 Gateway proof。
- 响应未知、异常或进程崩溃不能撤销第一阶段。Legal Hold 或延长保留期若涉及已授权
  且未获得物理删除回执的录像，返回 `RECORDING_DELETION_ALREADY_AUTHORIZED`。
  不将“已拒绝保全承诺”显示为“已经保全”。其他租户以及未授权对象的保全继续可用。
- EXECUTING 超过两分钟且行锁可取得时，按原精确身份、新 Epoch 恢复；调用已有
  Tombstone 幂等协议。重试耗尽进入 FAILED，保留授权、未知结果和零 Receipt。
  这不承诺取消旧的存储请求，也不宣称远程幂等执行完全没有重叠。
- 数据库触发器防止旧策略写入绕过保全拒绝，防止抹去授权，禁止旧 Worker 在没有
  授权时进入 EXECUTING。策略写锁与录制插入共享锁还覆盖迟到录制的旧快照：行级
  触发器重读已提交策略，合并 Hold 和保留期限。
- 公共 API/RPC/SDK 字段、版本和能力名保持；新增错误复用已有治理拒绝。

## 验证

`make test-recording-retention-order` 创建独有、随机密码、仅 loopback 的 PostgreSQL
17 容器，结束只清理自己的容器与临时目录。macOS 先核验 OrbStack；默认正常生成
Proto，本机已有输出可显式跳过生成。测试连接工厂只允许精确 IPv4 loopback，避免
环境代理对本机测试连接的干扰，不改 JVM 或生产代理配置。

十四项数据库测试实际执行通过，覆盖未知响应、崩溃、Hold 先到、无能力、正常回执、
新 Epoch 恢复、耗尽、旧 Worker、旧策略/直接投影、旧队列迁移拒绝、跨连接策略先到、
调用中保全拒绝、并发旧插入与其他租户保全。远程 proof 使用受控 Gateway 返回；
真正 Storage Helper 的对象删除由完整 Integration/GameDay 验证。

常规 Java **650 passed / 0 failed**；数据库类没有环境变量时跳过 14 项，不能用该
常规结果替代独立 Gate。独立脚本检查 JUnit 非零执行且零 skip/failure/error，并
已接入主 CI。N/N-1 Gate 增加 V134 的扩展式结构和旧派发拒绝声明。

私有证据：`/tmp/agentbrowser-retention-order-regression.json`、
`/tmp/agentbrowser-retention-order-final.log`、`/tmp/agentbrowser-retention-java.log`、
`/tmp/agentbrowser-retention-compatibility.json`；本机完整 OrbStack Integration exit 0，真实 MinIO 物理删除、全部版本清空和审计链
校验通过。该运行覆盖两阶段授权主逻辑，随后批量继续处理语义由独立 Gate 验证；
本次最终精确提交仍须以自己的 CI 收尾。

## V134 迁移 Runbook

### 预检与计划

1. PostgreSQL 17；先在独立测试库/目标 Staging 验证，盘点表规模、写入率、复制延迟
   和升级窗口。仓库本机测试不替代目标数据量验证。
2. 停止旧版删除 Worker 的新入队和派发，完成/核对全部关联未删除 Recording 的旧
   Job。`attempt=0` 也可能是已派发事务回滚，不能据此证明未删除。
3. 只读检查未解决行数：

   ```sql
   SELECT count(*) FROM recording_retention_deletion_jobs job
   JOIN session_recordings recording USING (tenant_id, session_id, recording_id)
   WHERE recording.deleted_at IS NULL;
   ```

   必须为零；否则依旧协议与真实对象/版本/Tombstone 核对，不得删队列、改计数或
   伪造 Receipt 来绕过。损坏/未知数据须保留证据并由运维处理后再升级。
4. Flyway 在事务内应用 V134，先取 Job/Recording 表锁，锁等待最多五秒；预检不满足
   则整笔失败。Nullable 列与触发器不批量回填，已有对象和证据不删除。
5. 上线新 Worker。确认正常删除有授权、物理 proof、Receipt；未知结果无 Receipt，
   保全请求明确拒绝；监控旧 Worker 拒绝、FAILED、遗留 EXECUTING 与锁等待。

### 回退与风险

- 迁移前失败会事务回滚。迁移后保留新增列、授权和触发器；应用可回退读取路径，
  但旧 Worker 的新删除会被数据库拒绝。应停止旧删除 Worker，向前修复；不能通过
  删除保护触发器恢复旧的不安全派发，也不能用迁移回退撤销已发出的对象删除。
- 策略写锁当前是跨租户粗粒度互斥，删除授权和录制插入使用共享侧；策略写入还可能
  等待正在完成的 Recording 行锁。大规模策略管理延迟、复制和目标云长稳仍需 Gate。
- 本次没有执行目标云 Apply、修改真实客户库或选择根许可证。

## 剩余目标

目标云逐版本 Legal Hold ON/OFF、取消/未知回执、真实 IAM/Object Lock 测试仍未实现；
客户视觉 Dataset、真实 OTP/IdP/支付/客户 SPA、Provider 服务端取消/计费回执、目标
Linux/硬件/多 Region 长稳、组织与许可证 Gate 均保留。完整区域授权/动作/独立
Outcome 仍需产品闭环。原十一项总目标保持 active。
