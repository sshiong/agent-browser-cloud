# Oracle 派发租约写放大修复与重启治理

> 日期：2026-11-12；正式 API 268 Operations / 368 Schemas 不变，无数据库迁移。

Oracle 空闲节点仍被真实 I/O PSI 关闭准入。本轮通过 Termius 对 `/proc/<pid>/io`
做十秒差分，并核对 cgroup 与容器 ID，确认最高写入的三个 PostgreSQL 进程属于本项目，
合计写入约 185 MB；不是据 CPU/RAM 或共享业务容器猜测原因。数据库统计中
`coordinator_dispatch_workers` 已累计 771,275 次更新，其他业务表远低于它。

`NodeCommandDispatchClaimService` 的 claim、取消快通道和 locality 查询此前每次都执行
无条件 Heartbeat UPSERT。修复后每次仍查询 PostgreSQL 权威状态，但对已续租且保留
至少三分之二租期的行不再更新或锁行；每三分之一租期续写。NOT EXISTS 避免正常热路径
拿冲突行锁，ON CONFLICT 条件在并发续租时二次检查；旧时间请求不能缩短新 Lease。
没有本地成功缓存，也没有修改派发频率、PSI 阈值、fsync 或持久化承诺。

验证：完整 Java 691 项通过、Spotless 通过。新增真实 PostgreSQL 测试直接提取 Java SQL，
999 次新鲜检查产生零次额外行写入；8 个并发通道只续租一次；过期恢复、旧请求不倒退和
注销后即时重注册均通过。测试加入主 CI，专用容器及匿名卷已清理。

服务器实际 ARM64 Control Plane 镜像重新构建并替换，健康检查通过；租约表十秒更新
10 次，第一次压力复测 I/O Full 从约 1.9 降至 0.62。四 Worker 强制重建后默认健康及
凭据不在环境变量检查 PASS。后续持续采样未达到稳定 OPEN，不能据首次下降宣称准入恢复。

另新增 `tools/local-dev/restart-control-plane.sh`：接收 Compose 全局参数，重启已运行的
Control Plane，等待 healthy 后强制重建原来运行的 Worker，使其重新加入共享网络命名空间，
逐个等待健康。不重启数据库、Browser Node 或其他项目。Shell 语法与模拟命令顺序验证通过；
服务器本轮使用相同顺序手动替换与重建，不将模拟测试说成脚本实机验收。

使用示例（在仓库根目录）：

```sh
sh tools/local-dev/restart-control-plane.sh -p your-project -f docker-compose.yml
```

对象存储、OIDC、完整 Agent/Reviewer/Vision 业务任务及 V16 生产 Gate 继续保留；四 Worker
健康和供应商单次 Responses 成功不能替代这些验收。供应商凭据、服务器地址及私有覆盖配置
不进入仓库。原四个业务容器保留，不执行全局 prune。

后续五秒进程 I/O 差分与 cgroup 核对表明：最高四个 PostgreSQL 写入进程已属于原有
`aitown-oracle-pg`，最高单进程写入 116,129,792 字节，第二进程读取约 40 MB、写入约
12 MB。此时宿主机 I/O Full 为 13.64%，Node 真实报告此前 14—17%，仍 CRITICAL/CLOSED。
本项目 PostgreSQL 不再进入该次采样前五位，原 Node 约写入 160 KiB。没有把其他业务
的压力伪造成本项目正常，也没有关闭门禁。现有业务短暂停止需用户明确选择，当前未停止；
因此本轮仍未通过 ARM 真实 Chromium/MCP 启停。无需重建或清空业务数据库来完成此修复。
