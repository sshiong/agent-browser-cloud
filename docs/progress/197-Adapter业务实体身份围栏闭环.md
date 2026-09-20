# Adapter 业务实体身份围栏闭环

> 日期：2026-09-21
> 范围：完全同名目标、虚拟列表业务实体身份、Application Adapter DOM 契约

## 问题

已有语义 Element ID 能识别 DOM 复用、常见业务键和行容器文本，但仍存在两个边界：页面中多个
目标的 role/name/control 与可见上下文完全相同且没有业务键时，DOM path 只能区分槽位，不能证明
槽位对应哪个业务实体；此外，Node 过去从目标父节点开始寻找显式键，Adapter 直接标在按钮上的实体
身份会被忽略。

## 实现

- Application Adapter 新增 `entity-identity` 命令和同名库函数。原始 CRM/支付/IAM 实体值只从
  `0600/0400` 文件读取，以至少 32 字节的租户/应用隔离 Key、namespace、entity type 和版本域执行
  HMAC-SHA256；输出只含：
  `data-agent-entity-hash`、`data-agent-entity-scope`、`data-agent-entity-type`。
- Browser Node 从目标元素自身开始，沿 DOM 祖先与 open Shadow Root host 查找最近的有效 Adapter
  三元组。Hash 必须是 64 位小写十六进制，scope/type 使用有界标识符；无效或不完整标记不生效。
- Adapter 三元组仍是非可信身份数据：只进入 Element ID/Target Revision 围栏，不提升 Capability、
  风险等级、权限或 Outcome。Node 在 Browser State 发布前再次哈希，原始实体值、Adapter Hash、
  scope/type 均不出现在公开 State、API 或 Audit。
- 对缺少任何语义上下文且 role/name/control/frame/sensitivity 完全相同的多个可见可用目标，Node
  保留观察但将其标为不可交互，禁止模型凭 DOM path 猜测。补上不同 Adapter 身份后两个目标恢复
  可交互，并产生不同稳定 Element ID。
- Region Resync 保守继承 Full State 的歧义拒绝；只有出现有效新实体身份，或后续 Full State 已
  证明不再歧义，才能恢复执行。

## 验证

- `make test-application-adapter`：13 项通过，覆盖确定性、跨实体/namespace 域分离、弱 Key 与非法
  component 拒绝，以及输出中不存在原始业务 ID。
- Rust 定向测试验证完全同名无实体上下文时 fail-closed，提供两个 Adapter identity 后恢复交互且
  Element ID 不同。
- Chrome 153 真实 CDP Gate 在同一页面渲染两个完全同名按钮，Adapter 标记直接位于按钮自身；两个
  目标均可交互、Element ID 不同，公开 Browser State 不含 Adapter Hash 或 scope。

## 边界

若页面和租户 Adapter 都无法提供任何稳定业务实体身份，系统选择拒绝执行而不是猜测。Adapter Key
轮换会有意改变 Element ID，需要重新 snapshot/find；该身份只解决目标绑定，不替代 Expected
Outcome、Provider Evidence 或高风险确认。
