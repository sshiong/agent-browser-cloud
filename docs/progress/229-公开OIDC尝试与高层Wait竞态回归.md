# 公开 OIDC 尝试与高层 Wait 竞态回归

> 日期：2026-09-28
> 范围：真实 URL Replay 的证据边界，以及 Integration Gate 的状态游标竞态。

## 公开 OIDC 尝试

尝试在 Duende 公开演示站上增加独立 Authorization Code + PKCE Client Replay。试验用例未进入正式 Dataset：真实 Chrome 在现有站内登录的用户名输入前持续报告 `document_ready_state=loading`、8 个活动资源请求且 `network_quiet_ms=0`，三次有界尝试均以 `PAGE_UNSTABLE` 拒绝动作。站点的这次外部加载状态无法证明 OIDC callback、Token Exchange 或 UserInfo，因此仍维持 [用户 11 项专项复核](224-用户11项专项复核与公开商业演示Replay.md)的“独立 OIDC Client 未验证”结论。未放宽页面稳定性门禁或外连白名单。

## Integration Gate 竞态

远端 CI 的 Gateway Fence 改动首次 `verify` 在高层 `agent-browser/wait` 创建时收到 HTTP 409。该接口使用刚获取的 `expectedStateCursor` 进行权威 CAS；背景状态更新可能使请求前的快照过期。集成脚本现在对明确的 `AGENT_BROWSER_ACTION_REJECTED` / `STATE_CURSOR_STALE` 最多重取快照三次，每次使用独立幂等键。其他 409 立即失败并输出错误，不把实际执行冲突吞掉。

验证：`bash -n tests/integration/smoke.sh`、`git diff --check`、`make docs-check` 通过；OrbStack `Running`、Docker context `orbstack`、Daemon `OS=OrbStack`；完整 `make test-integration` 通过，输出 `agent_browser_high_level_tools=true`。这是本机 Gate，远端 CI 状态单独核对。
