# Session 与 Group/Tag 列表批量投影闭环

> 日期：2026-07-31
> 适用范围：Control Plane Session、Workspace Groups、Workspace Tags 读取链路
> 数据来源：正式 PostgreSQL/JPA Repository

## 本轮结论

Session 列表、分组列表和标签列表的逐项读取已改为批量投影。列表长度增加时，不再按
每个 Session、Group 或 Tag 追加 Context、Ownership、Operation、Tag、Proxy 或成员
查询；响应仍来自正式 PostgreSQL 数据，不引入缓存快照、Mock、JSON 文件或内存权威状态。

本轮只关闭读取链路 N+1。Group/Tag 的批量生命周期写操作、服务端组合筛选和 Agent
大列表优化仍是独立待办，没有被本轮误计为完成。

## 已完成

### Session 列表

- 一次批量查询列表中每个 Session 的最新 `SessionContext`；
- 一次批量读取 `CoordinatorOwnership`，并继续以 Ownership Term 与 Context Term
  的最大值作为返回值；
- 一次批量读取列表 Session 的 Active Operation；
- 一次读取 Session/Tag Assignment，再一次读取涉及的 Tag 摘要；
- 一次批量读取 Session 的 Proxy Binding Assignment；
- 单 Session Detail 仍保留精确单对象读取路径，不扩大查询范围。

因此 Session 列表读取次数不再随分页内 Session 数量线性增长。当前一次非空列表由
Session Page/Count 及 Context、Ownership、Operation、Tag Assignment、Tag、Proxy
Binding 的固定批次组成。

### Workspace Groups

- 先按 Tenant 一次读取全部已分组 Session，再在应用层按 `groupId` 建立有序成员投影；
- 未分组 Session 保持独立一次查询；
- 不再为每个 Group 分别查询成员。

### Workspace Tags

- 一次读取 Tenant Tags、一次读取 Tenant Sessions、一次读取 Tenant Tag Assignments；
- 在应用层以受租户约束的数据建立 Tag 成员投影；
- 多 Session 的 Tag Summary 改为一次 Assignment `IN` 查询加一次 Tag `IN` 查询；
- 不再为每个 Tag 逐次查 Assignment/Session，也不再为每个 Session 逐次查 Tag。

## 安全与一致性

- 所有批量方法继续显式携带 `tenantId`；Tag/Proxy Assignment 查询保持租户条件；
- 最新 Context 只选择每个 Session 最大 `contextEpoch`；
- Active Operation 出现重复时 fail-closed，不静默选择任意一条；
- 返回顺序仍由既有 Group/Tag/Session 排序契约控制；
- 批量投影只用于只读响应，不改变 Operation、幂等、CAS 或审计写入边界。

## 回归证据

- `WorkspaceGroupApplicationServiceTest` 证明列表不调用逐 Group 成员查询；
- `WorkspaceTagApplicationServiceTest` 证明列表及多 Session 摘要不调用逐项查询；
- `SessionApplicationServiceTest` 证明 Session 列表只调用批量 Operation/Tag/Proxy
  投影；
- `JpaSessionRepositoryTest` 证明列表不调用逐 Session Context/Ownership 查询；
- Control Plane 275 项测试、Browser Node 全 Workspace 测试和 Web 18 个文件/60 项
  测试通过；
- 全仓 Lint、OpenAPI/Proto/JSON Schema Contract、N/N−1 升级兼容通过；
- Tauri Rust Lint/2 项测试、共享 Web Production Build 和 macOS 本机无安装包构建
  通过；
- 完整 PostgreSQL/双 Control Plane/三 Browser Node/MinIO/mTLS Integration 通过，
  既有 Session/Operation/迁移/审计主链无回归。

## 仍未完成

- Group/Tag 批量启动、停止、移动、归属等生命周期写操作及对应 Operation；
- 按 Group、Tags、状态、区域等组合的服务端筛选与分页契约；
- Agent 大列表 N+1 和大规模分页/容量证书；
- 跨 Region 列表事件总线和大规模慢客户端/Ingress 长稳；
- 目标集群数据库连接池、热点 Tenant 和双 Coordinator 长稳。
