# Recorder 静态扫描器夹具与 Linux 执行竞态修复

> 日期：2026-10-23
> 基线：2e18d95fecae641a945a5ed9a719391a3cfb8232
> 范围：Recorder 测试准备与原十一项第五项的 CI 证据。

## 真实 CI 失败

主 CI **39812875183** 的 Verify 在
`recording_redaction_failure_acks_but_never_queues_the_raw_frame` 失败：扫描器进程
启动返回 `Text file busy (os error 26)`，Ready 通道关闭。尚未进入损坏 JPEG 的脱敏
断言；新增 Legal Hold 存储 GameDay 没有执行。Operator 成功，不能把它当作完整 CI。

旧夹具每次测试用 `tokio::fs::write` 生成新的 Python 可执行文件，随后改权限并立即
执行。CI 的证据确认失败发生在 exec；不据此推断某个具体 Tokio 内部关闭时序，也
不把失败追溯为生产扫描器或 Legal Hold 逻辑缺陷。

## 修改

- 把相同脚本正文移入仓库的可执行静态夹具
  `tests/fixtures/recording-privacy-scanner-passthrough.py`。
- 测试从 `CARGO_MANIFEST_DIR` 解析并 canonicalize 精确夹具路径，去除临时脚本
  写入与 chmod。仍启动真实 Python 子进程，不改生产扫描器调用方式。
- 保留损坏 JPEG 必须 ACK、不入帧队列、capture_failed=true、JPEG decode 错误
  和完整遮罩像素等原断言，不增加跳过或失败重试。

## 验证与后续

- `cargo test --locked ... -p session-recorder`：**15 passed / 0 failed / 1 ignored**。
- Recorder 全 Target 严格 Clippy、Rust 格式和 diff 检查通过。
- 静态夹具正文与改动前字符串逐字节比较相同。
- 新提交仍须核验自己的 Verify、扫描、PostgreSQL、Integration、Object Storage/
  Recording GameDay、Operator 与桌面结果。失败流程不直接 rerun 代替源码修复。

Legal Hold 执行器仍未接 PostgreSQL 队列、mTLS RPC、策略 API/UI、删除并发联锁与
未知结果恢复；目标云 IAM/账户、客户数据集和原十一项外部 Gate 继续保留。

私有证据：`/tmp/agentbrowser-hold-ci-first.json`、
`/tmp/agentbrowser-hold-ci-failed.log`、`/tmp/agentbrowser-hold-recorder-fixture.log`、
`/tmp/agentbrowser-hold-recorder-clippy.log` 及对应退出状态。
