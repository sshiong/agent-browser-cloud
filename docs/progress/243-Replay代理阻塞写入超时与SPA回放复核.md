# Replay 代理阻塞写入超时与 SPA 回放复核

> 日期：2026-10-02
> 范围：公开 Replay 测试出口代理、真实 socket 回归、完整 17 例复核。

## 可重复的代理缺陷

进度 239 已修复 CONNECT relay 退出后没有关闭浏览器 TCP 的问题。进一步检查发现，
relay 的 30 秒超时仅作用于 `select`，两个 socket 仍为无限期阻塞模式。对端停止读取、
内核发送缓冲区写满时，`sendall` 不会返回，relay 无法继续执行空闲超时检查。

新增真实 `socketpair` 回归，缩小目标发送缓冲区，让目标对端完全不读取，分别验证
浏览器方向和上游方向。旧代码下，两种方向均在测试设定的 0.1 秒超时后超过一秒仍不
退出；测试主动清理连接后线程才退出。测试只使用合成字节，不访问外站或读取凭据。

两个连接现在都设置与 relay 相同的 socket 超时，使每次最多 64 KiB 的完整写入也有
时间上限。传输超时、复位或断开结束 relay，再由既有 CONNECT Handler 收尾关闭连接。
部分写入不重试，不重放加密流，不输出可能包含端点的原始传输错误。
默认 30 秒、精确 Host/公网地址校验和拒绝直连保持原有策略。

[Python socket 文档](https://docs.python.org/3/library/socket.html#socket.socket.sendall)
说明，`sendall` 的 socket 超时约束整次发送，而不是每次部分写入后重新计时。

## 验证

- 修复前，Proxy Tunnel 四项测试中的阻塞写入测试在两个方向均失败；原 EOF、空闲收尾
  与明文 OIDC 回调拒绝回归通过。
- 修复后 `make test-replay-gate` **18 项全部通过**，含真实双向阻塞写入、TCP 收尾、
  OIDC 验证器与 Dataset 授权契约检查。
- Python 编译、差异空白检查通过。
- 此前 Recording 最终 JPEG 修复提交 `3cf96fe6373f2f2db5f896b183085311ab6393f4`
  的 `ci` run `36978182501` 和 `desktop` run `36978182483` 均全部通过；CI 包含新增
  真实 OCR/OpenCV 隐私 Gate、Integration、Object Storage/Recording GameDay 与 Operator。

## 完整 17 例真实回放

修复后使用 OrbStack、Chrome 154 与原 Dataset/白名单执行完整矩阵，附加同一 Browser
的只读 CDP 连接。该运行没有取得 PASS：Session `ses_7a7fe19377004513` 的
`public-spa-type` 在输入前返回 `PAGE_UNSTABLE`，原五秒有界稳定等待已执行并耗尽。
前置 `GET_CURRENT_STATE` 已验证，`TYPE_TEXT` 失败，未修改 Harness 为盲目重发输入。

UTC 07:33:35 的独立 CDP 仍观察到练习站 `SOCKET_IO / POST / XHR / script`，
约 35.3 秒尚无响应；同一 Page 已有两次 SPA Document 加载完成。该采样中唯一的
在途 XHR/Fetch 是此请求。UTC 07:33:37 的 Node 失败诊断显示 Document 为 `complete`，
DOM/Layout/Focus/Route Quiet 均约 5.5 秒，Network Quiet 为零且有一个 `XHR:script`
在途。浏览器和 Node 的证据都不能把该请求视为已完成。

辅助诊断仅记录固定类别、状态和持续时间，没有记录 URL Query、Header、POST 正文或
敏感输入，因而不能为此请求的业务事件作定性。也不能据本轮证据追溯此前所有 POST，
或将历史 Document 未完成、Socket.IO 在途统一归因为阻塞写入缺陷。

下一步需检查跨 Document 网络归属及事务保护：旧上下文请求不能仅因年龄、导航或
页面 load 被标记完成，未知写入仍须保留事务证据；同时要确认当前页面的网络静默
是否包含已经失去页面上下文的请求。只有权威上下文证据和回归才能调整此边界。

日志位于本机私有目录 `/tmp/agentbrowser-full17-writebound.ElNi7k/`。测试和观察器
均已退出，Harness 已清理本轮容器、Profile 与证书。完整 17 例连续稳定性、目标 Linux
及真实客户/外部系统 Gate 仍未完成。本提交推送后的 CI/desktop 仍需按精确提交核验。
