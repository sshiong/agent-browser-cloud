# 可复用 Proxy Binding 创建时闭环

> 日期：2026-07-30
> 状态：租户配置、创建时不可变快照、独立 Runtime Allocation、真实出口校验和
> Web/Tauri 共享 UI 已完成；多 Provider、后台主动探测与运行中 Rebind Operation
> 仍未完成

## 本轮关闭的缺口

原实现只有一个全局 Static Proxy Provider 和与 Session 一对一的临时
`proxy_allocations`。创建向导明确禁止“复用现有 Allocation”，这是正确的安全边界，
但缺少可复用的管理面配置。

V057 将两类身份分开：

- `proxy_binding_profiles` 是租户级、可复用的管理面配置；
- `session_proxy_binding_assignments` 在 Session 创建时固化 Profile Version、
  Provider、Region、Expected Exit 与 Secret 引用；
- `proxy_allocations` 仍是每个 Session 独立的运行租约，并新增 Binding Profile、
  Binding Version 与 Expected Exit 快照；
- `SessionContext.proxyBindingId` 继续只表示 `pxy_…` Runtime Allocation，不允许跨
  Session 复用；
- `SessionView.proxyBindingProfileId` 单独投影 `pbind_…` 管理配置身份。

因此“复用配置”和“共享运行租约”不会混淆。后续编辑或禁用 Profile 不会静默改变已经
创建的 Session；既有 Session 启动时仍按不可变快照创建自己的 Allocation。

## 正式 API、权限与审计

新增正式接口：

```text
GET    /api/v1/proxy-bindings
POST   /api/v1/proxy-bindings
PUT    /api/v1/proxy-bindings/{bindingProfileId}
DELETE /api/v1/proxy-bindings/{bindingProfileId}
```

- List 使用 READ 权限；创建、更新和删除使用 ADMIN 权限；
- 所有查询按认证 Tenant 过滤，不接受请求体选择其他 Tenant；
- 创建、更新、删除均要求 `Idempotency-Key`；
- 更新要求 `expectedVersion`，使用 PostgreSQL/JPA Version CAS；
- 同租户名称大小写不敏感唯一；
- 已被任何 Session 快照引用的 Profile 禁止删除，只能禁用；
- Secret 只保存 `vault://`、`secret://` 等不透明引用；API 永不返回引用正文，只返回
  `credentialConfigured`；
- Audit 只记录名称、Provider 和 Enabled，不记录 Secret 引用；
- 错误使用稳定 Envelope、Reason 与 Request ID，不向浏览器暴露堆栈。

创建 Session 的正式请求新增可选 `proxyBindingProfileId`。服务端在同一个创建事务中
校验 Tenant、Enabled 和 Region，然后保存不可变 Assignment。重复创建请求返回同一
Session，不会重复生成 Assignment。

## Runtime 与健康状态

Runtime 启动仍先创建独立 `pxy_…` Allocation，再把该 ID 进入 Session Context 和
Node Command。Browser Node 的隔离 Network Helper 完成实际出口观察后：

- Control Plane 校验 Allocation、Tenant、Session 和 Expected Exit 快照；
- 匹配后 Allocation 进入 `BOUND`；
- 对应 Binding Profile 记录 `lastVerifiedExitIp` 和 `lastHealthCheckedAt`；
- Profile 已禁用时仍保持 `DISABLED`，不会因旧 Session 的成功验证重新开放给新
  Session；
- 不匹配时 Runtime 启动 fail-closed，不回退 Direct。

当前 Static Provider Fleet 只支持已配置的 Provider ID 与 Expected Exit，因此创建或
更新 Profile 会拒绝未部署的 Provider/Exit。这个限制避免 UI 声称支持数据面实际上无法
执行的出口。

## Web / Tauri 共享体验

“代理与出口”页面新增真实 Proxy Binding 管理区：

- 展示 Profile ID、Version、Enabled/Health、Region、Expected Exit 和 Secret
  是否已配置；
- 管理员可创建、编辑、禁用和删除未使用 Profile；
- 编辑时不回显 Secret 引用，留空保留原值；
- 删除使用二次点击确认，服务端仍执行引用 Gate；
- 失败显示稳定 Reason 和 Request ID；
- 状态同时使用文字和颜色，不只依赖颜色。

六步创建向导的 Network 步骤移除原禁用占位，读取正式 Binding API：

- 默认仍为“系统托管出口”；
- 只列出 Enabled 且 Region 兼容的 Profile；
- 切换 Direct 或 Region 不匹配时会清除旧选择；
- 提交真实 `proxyBindingProfileId`，不写入 Metadata、`localStorage` 或 Mock；
- Review 明确显示 Profile ID，并区分配置档案和 Session Allocation。

React 页面、组件、API Client、TanStack Query 与权限逻辑继续由 Web 和 Tauri 2 共用。

## 迁移与回滚边界

V057 是 expand-only：

- 新增两张表；
- `proxy_allocations` 只增加三个 Nullable 列；
- 新外键和 Check 先 `NOT VALID`，再 `VALIDATE`；
- N−1 Writer 可继续写旧 Allocation，不需要新列；
- N−1 应用会忽略 Profile/Assignment 表，不会因 JSON 未知字段失败。

数据库回滚不需要删除 V057 对象。应用回滚存在语义边界：旧 Control Plane 不读取
Assignment，会退回全局 Static Provider。因此回滚前必须停止创建带
`proxyBindingProfileId` 的新 Session，并确保已经带 Assignment 的 Session 继续由新
版本 Control Plane 启停；不能只回滚应用并假设 Profile 语义仍生效。

## 可重复验收

```text
./gradlew -p apps/control-plane spotlessCheck test
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console test
pnpm --dir apps/web-console build
make contracts-check
python3 tests/upgrade/n-minus-one-gate.py
./tests/integration/smoke.sh
```

实测结果：

- Java 全量 Check/Test 通过，新增创建、Secret 不回传、Session 快照和 Region
  拒绝测试；
- Web 15 个测试文件、52 项测试、ESLint、Prettier 和 Production Build 通过；
- OpenAPI 3.1 正式契约通过 Redocly，V057 进入 N/N−1 Gate；
- 完整 Integration 使用 PostgreSQL 17、Redis、mTLS Browser Node、Network Helper
  和真实代理出口，验证幂等、Viewer 403、不可变 Version 0 快照、禁用后旧 Session
  启动、`BOUND` Allocation、Expected Exit 与数据库复合关联；
- 本地真实浏览器验证 `/proxies` 空态、创建抽屉、真实创建、Profile 卡片和六步向导
  选择；Console Error 为 0。

## 仍未完成

1. 多 Provider Adapter、每 Profile Endpoint/认证解析和 Provider 健康/容量模型；
2. 由目标云 Secret Manager/KMS 完成 Secret 引用授权、解引用、轮换与节点下发；
3. 不依赖 Session 启动的后台主动健康探测、熔断和自动隔离；
4. 运行中 Session 的显式 Proxy Rebind Operation、安全点、Drain、Node ACK、
   State Resync 与 Business Recovery；
5. Proxy Reputation、成本、配额、IP 池、历史和大列表批量投影；
6. 目标 Linux 多 Node/多 Provider 长稳、网络分区和生产凭据轮换证书。
