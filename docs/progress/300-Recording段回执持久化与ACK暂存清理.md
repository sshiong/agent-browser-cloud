# Recording 段回执持久化与 ACK 暂存清理

> 日期：2026-10-28
> 基线：c75de6e1757cbab491cce2d38395ed5210a752a3
> 状态：Node Journal/只读核对/Helper ACK 组件通过验证，正式 Recorder 与控制面仍未接入。

## 实际实现

- helper-contracts 增加版本化段 Receipt 与独立 ACK 命令。Receipt 绑定 Scope、段描述及
  原生上传 Proof，校验身份/Epoch/大小/脱敏版本/Hash/两个精确版本计数后计算关联 Hash。
  旧回执无 ACK 字段；客户端要求完整且精确的 ACK 回显，不回退普通上传，普通上传客户端
  也拒绝把 ACK 响应当成本次新上传。
- Node Journal 新增 additive SQLite recording_upload_receipts 表；使用 FULL synchronous
  和 IMMEDIATE 事务，成功提交后才返回 ACK 请求。段回执不可 UPDATE/DELETE，相同段只能
  重放同一 Receipt；同录制后续段也必须保持原 Scope 与 Archive-bound Scope Hash，不能
  混合不同 Placement/Epoch/Archive 的结果。读取既有回执时重验其 Hash。
- ObjectArchive 增加只读验证模式：必须已存在精确 AUTHORITY、PREPARED、COMMITTED，
  回读原 Version ID/所有权 Nonce/大小/原生 ON/原字节及 Marker，重算原 Proof。缺少意图
  或回执时直接拒绝，绝不因 ACK 创建意图、补 Proof 或重传像素。
- Helper ACK 在原 Profile 锁内工作，复用路径隔离/O_NOFOLLOW/逐帧 v2 证明校验。
  首次清理必须核对远端证明、当前源文件长度与 Hash，然后以 create-new/0600 写本地
  ACKED 标记、同步文件与父目录，再重验 dev/inode/长度/mtime 后 unlink 并同步目录。
  错误 Proof、冲突 ACK、替换文件、未知结果均保留源文件，不解除云端 ON。
- 标记落盘后、unlink 前崩溃可沿相同 ACK 继续；已清理后重放也成功。不匹配的重建源文件
  不能借历史 ACK 被清除。本地标记是私有恢复元数据，不包含对象位置或像素。

关联 Hash 用于绑定请求，**不是 Helper 对 Node 数据库已落盘的独立密码学证明**。
Helper 不直接读取 Node SQLite。实际先写 Journal 再发 ACK 的顺序由本轮组件验收执行，
正式 Recorder 尚未调用这条链，后续接入必须保持该顺序。控制面授权、完整 Manifest、
并发 writer seal/所有权交接仍缺，不能宣称生产录制已经自动清理或可安全 OFF。

## 验证证据

- 客户端9项、契约5项、SQLite Journal11项，共 **25 passed / 0 failed**。新增验证：
  SQLite 重启重放一致、真实 SQL 不可更新/删除、Scope/Archive/Proof 冲突保持旧回执，
  双连接并发只接受一个 Proof；ACK 缺失/Hash/Proof/Sequence 错误及旧 Helper 不成功、
  不出现第二次请求或普通上传 fallback。
- OrbStack Running / context orbstack / OS=OrbStack 核验后，真实 MinIO 专用 Gate 共
  **8项库 + 3项 Unix IPC = 11 passed / 0 failed / 0 skipped**。
- 新 IPC 场景实际上传后，将 Receipt 写入真实 Node SQLite、关闭并重开数据库，随后调用
  实际 Client/Helper ACK。错误 Proof 不生成清理标记；合法 ACK 删除源文件；丢回执式
  重放成功；重建“标记已持久、源文件尚存”的窗口后也可完成清理；重建无关文件则拒绝
  并保留。独立 S3 oracle 确认整个 Session 对象/Journal Version 集合完全相同。
- 崩溃窗口通过重建状态模拟，未实际 kill 进程或注入 fsync 失败；像素与 v2 扫描证明沿用
  明确合成夹具，未重新运行 OCR 或真实 Recorder，不扩张验收范围。
- 真实11项之后进一步收紧 SQLite 的 Archive Scope Hash 围栏，最终25项回归及全
  Workspace/全部 targets 严格 Clippy 再次通过；本提交 CI 将验证最终源码完整组合。
  格式和 Git whitespace 检查通过。

私有日志：/tmp/agentbrowser-upload-ack-unit-final.log、
/tmp/agentbrowser-upload-ack-minio-final.log、/tmp/agentbrowser-upload-ack-clippy-release.log。
MinIO/mc 使用官方精确源码，mc shell 容器显式复用本机已缓存 node:20-alpine 基础镜像，
与 progress299 的本地网络约束相同，不改变二进制 Release/Commit 验证。

## SQLite 部署边界

只 CREATE 新表与不可变触发器，不改写旧命令/Event/Lease 行，不为历史段伪造 Receipt。
旧程序可忽略新表；应用回退时保留回执和本地 ACK 标记，不通过删除或换 Proof“恢复”未知
上传。新回执表有段号和 JSON 大小上限；元数据保留治理、正式 Recorder 背压和磁盘/长稳
仍须验证，不能把已提供清理接口写成持续录制 staging 已有界的生产证书。

## CI 与资源收尾

基线 c75de6e 的主 CI **40679550959**、Desktop **40679550767** 精确 SHA 均最终
completed/success，所有 Job 成功；Verify 日志确认上传 IPC 用例与 UNKNOWN 拒绝 Gate
实际执行，含完整 Integration/GameDay。progress299 已收尾。本提交仍须自己的完整 CI。

本轮专用容器/网络已由定向 cleanup 回收，另根据本轮构建日志的 image digest 确认来源、
核对没有容器引用后，删除两个本轮未用 MinIO/mc 测试镜像。本任务测试容器/网络均为0，
其余8个容器及共享基础镜像/缓存、用户文件保留，未执行全局 prune。
清理记录：/tmp/agentbrowser-upload-ack-cleanup.json。

## 继续保留的目标

PostgreSQL 活动录制登记与权威上传授权、Node/Recorder 正式调用与清理完成恢复编排、
最终 Manifest 原子 ON、并发关闭写入/所有权交接、最终 Hold 队列安全承接、跨 Node、
真实进程/网络故障和目标云 Gate 均未完成。真实外部系统、客户 Replay、Linux/Region/
组织与许可证等原十一项范围继续保留，当前不满足 V16 生产发布条件。

后续收尾：f676adf主CI40686294652、Desktop40686294673精确SHA全成功，真实ACK用例
已确认实际执行。progress301增加存储层原生Manifest与v2参与者CAS封口；正式调用、
PG授权、跨Node/旧写入器围栏和Hold显式交接仍保留。
