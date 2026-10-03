# Integration 模拟 Chromium 补齐文档身份协议

> 日期：2026-10-03
> 基线：`d4c1190229948d2a0cb8fb33165ace3d16d5e3a1`

## 失败与修复

`bf36093` 的 [CI 37103375143](https://github.com/sshiong/agent-browser-cloud/actions/runs/37103375143)
和 `d4c1190` 的 [CI 37104033649](https://github.com/sshiong/agent-browser-cloud/actions/runs/37104033649)
均在 Integration `smoke.sh:3236` 的首份 Browser State HTTP 200 断言失败。
两轮 Verify 前置检查与构建通过，Operator 成功；各自 Windows/macOS Desktop 全部成功。
主 CI 均未通过，不能沿用此前“仍在运行”的状态。

259 引入的正式采集先后查询 `Page.getFrameTree`，而 Integration 的独立
`fake-chromium.sh` 只为未知方法返回空结果。它未提供根 Frame/Loader，
采集因此没有合法文档身份。修复为每个模拟 Page 返回自己的根 Frame 与
Loader，并在 `Page.reload` 后增加文档代数。同一 URL 重载不继承 Loader。
不存在的 Page 返回 CDP error；没有放松正式 Node 的身份、超时或输入围栏。

## 验证

新增回归实际启动该 Fixture，通过其 WebSocket/CDP 检查：

- 采样前后根 Frame/Loader 非空且相同，根 Frame 没有 `parentId`；
- 同 URL 重载保持 Frame、更新 Loader；
- 不同 Tab 的 Frame/Loader 不同，Tab 关闭后拒绝读取。

回归在 Git 基线旧 Fixture 实际失败：缺少 `frameTree`；新 Fixture 通过。
它已纳入现有 `make test-replay-gate`，该 Gate **31 passed**。
日志：`/tmp/agentbrowser-frame-tree-fixture-old.log`、
`/tmp/agentbrowser-frame-tree-fixture-tests.log`。

完整 OrbStack Integration **通过，exit 0**，含动态微批、Outcome、Reviewer 风险
路由、Profile 恢复、Recording 物理删除及最终 `audit_chain_valid=true`。
运行前核对 Running / orbstack / OS=OrbStack；测试使用基线正式 Node 加此次
Fixture，不混入尚未提交的稳定区域代码。日志
`/tmp/agentbrowser-frame-tree-fixture-integration.log`。

## 剩余边界

本修复使测试替身支持正式 CDP 协议，不能替代真实 Chrome、公开十七例
连续稳定性、区域动作授权和独立 Outcome。稳定区域证据开发仍在继续，
尚未接入正式动作路径。十一项目标的外部 Provider/客户授权、真实 OTP/IdP/
支付、目标 Linux/云/硬件/多 Region、组织签字与许可证权利人决策仍保留。
