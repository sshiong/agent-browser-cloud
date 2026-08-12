# VNC 实时计量与每 Actor 成本归因闭环

> 日期：2026-08-12
> 状态：代码、迁移、契约、SDK 与本地回归已完成；目标 Linux 8 Client 长稳仍是生产 Gate。

## 目标

在 VNC 与 Agent 并存、真人输入优先但不切断 Agent 的前提下，把每个远程桌面连接真实成功
转发的字节、配额等待时间和限速批次形成可重放、可解释的权威账本，并按 Actor 和版本化企业
费率归因成本。不能用前端曲线、固定计时器或 Prometheus 高基数 Actor 标签伪造结果。

## 已完成

- RFB Gateway 只在 WebSocket 二进制帧实际写成功后累计 `forwardedBytes`，调度前的真实配额
  延迟累计为 `quotaWaitMillis`，非零等待批次累计为 `throttledBatches`；输入队列完全不参与
  计量和节流，Agent/真人协作语义不变；
- 每条连接维护独立单调计数，每五秒仅在数值变化时通过既有 Node Journal 事件链上报，断开时
  再发送最终累计值。Event ID、Node Sequence、Context Epoch 和现有 Inbox 共同提供重放边界；
- Proto 10—12 为 additive 字段。N−1 Control Plane 会忽略新字段，新 Control Plane 对旧 Node
  的缺失字段按零处理，不引入滚动升级硬依赖；
- V094 向参与者投影增加带零默认值的累计量、成本、未定价字节和最近费率字段；约束先
  `NOT VALID` 再独立 `VALIDATE`。独立 `remote_desktop_usage_ledger` 按 Event ID 幂等保存真实
  增量，参与者短期历史清理不会删除成本账本；
- Control Plane 对每个连接使用行锁和 `max(old, reported)` 单调合并，只对正向字节增量计费，
  重放、重复或乱序累计值不会重复计费；
- 企业费率新增 `remoteDesktopEgressGibUsd`，创建费率时写入新的不可变 pricing version。
  成本记录同时保存 pricing version 和当时的每 GiB 单价；费率缺失时不静默按零结算，而是把
  字节计入 `unpricedForwardedBytes` 并在 UI 标记 `RATE MISSING`，供后续对账；
- 在线参与者 UI 展示真实转发 MiB、配额等待和已归因出口成本；Enterprise Operations 展示
  每个费率版本的 RFB 每 GiB 单价。Web 与 Tauri 继续复用同一套 React 组件和 API Client；
- OpenAPI 与 TypeScript/Python/Go/Java SDK 已同步，未把 Actor ID 暴露为 Prometheus 标签。

## 已验证

- Java 全量单测通过，新增用例验证单调累计、正向增量计费和重复事件不重复入账；
- Rust Gateway 20 项测试通过，新增用例验证成功转发字节、等待毫秒、限速批次的累计与最终清理；
- Web 70 项测试、ESLint、Prettier、Proto/OpenAPI lint 和 N/N−1 安全迁移 Gate 通过；
- V094 明确禁止列删除/改名/类型收缩，新增字段均具兼容默认值，新增 Check 约束经过在线验证；
- 四语言 SDK 已从同一 OpenAPI 重新生成，最终漂移 Gate 纳入提交前全量验证。
- `make test-integration` 通过：空库 94 个迁移后 `health=UP`、`public_tables=108`，双 Node
  迁移、资源调整生命周期、晚到 ACK 对账和 `audit_chain_valid=true` 均保持通过。
- GitHub 冷机的独立 Operator E2E 在业务代码运行前遇到 `cgr.dev` 固定 digest Blob 返回 500；
  测试脚本已为三个固定镜像 build 增加最多五次递增退避，digest 与供应链来源保持不变，重试
  耗尽仍失败，避免把外部 Registry 瞬断误当成产品回归或通过不安全 fallback 掩盖问题。

## 仍未完成

1. 目标 Linux 正式 Chromium/x11vnc 的 8 Client 长稳、弱网和真实出口账单核对；
2. 跨 Region Desktop Relay/Workflow 及全局账本复制一致性；
3. 生产压缩编码、硬件 Codec/GPU Helper 的转码成本拆分；
4. 未定价字节的受控补价审批与财务系统导出属于后续企业运营深度。
