# Agent Worker

`agent-worker/v1` 是独立于 Control Plane 的最小权限调度故障域。它只接收不透明的
`jobId/taskId`、一次性 Claim Token、Epoch 和 Lease，不接收 Prompt、Plan、网页数据、
Capability Token、客户凭据或任意 Runner 命令。

Worker 仅能调用五个固定接口：Claim、Start、Heartbeat、Drive、Fail。真正的 Tool
执行仍由 Control Plane 的 Capability/Operation/Outbox 安全内核完成。生产部署必须使用
HTTPS/OIDC、独立 ServiceAccount、只读根文件系统、无宿主挂载，并只允许访问 DNS 和
Control Plane。
