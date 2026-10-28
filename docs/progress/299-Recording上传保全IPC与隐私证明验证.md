# Recording 上传保全 IPC 与隐私证明验证

> 日期：2026-10-28
> 基线：fce32567a49df20526a206edae870d2ff356ba92
> 状态：Helper IPC/客户端与真实 MinIO 已通过；控制面/Recorder 产品接入仍未完成。

## 实际接入

- 内部 JSON IPC 增加独立 COMMIT_RECORDING_HELD_SEGMENT 命令，携带精确 Scope 与
  段描述，不给旧命令增加可被旧 Helper 静默忽略的“保全开关”。不支持/拒绝/缺少 Proof
  均失败关闭，客户端没有普通上传 fallback。Schema 1 及原命令保持兼容。
- Scope 移至 helper-contracts 共用；保留既有字段顺序和 snake_case 序列化，测试固定
  已持久化 v1 JSON 字节。旧 StorageRecording 回执缺少 uploadHold 时仍不具备保全证明。
- Helper 在原 Profile stripe 锁内将新命令规范化，复用安全目录、O_NOFOLLOW、文件大小
  与最终内容 Hash 校验，以及已有逐帧隐私证明验证器。新路径额外强制 v2，不能按历史
  v1 元数据跳过完整帧证明；验证成功后才调用 progress298 原子 ON 原语。
- 新回执只含操作/authority/placement Epoch、Scope/Proof Hash 和两个精确版本的计数。
  不向客户端返回对象 Key 或 Version ID。客户端重验 Recording/Sequence/大小/帧数/
  脱敏计数/Policy Version、非最终化状态，以及精确请求对应的 Proof；缺少或错误即拒绝。
- Held 段提交后保留本机 staging 文件，丢失 IPC 回执仍能沿原字节和意图回读。当前没有
  Node Journal 持久 ACK/清理协议，不能启用持续生产录制并让 staging 无界增长；该协议
  与 Recorder 正式接入仍是后续要求。旧普通上传沿既有清理路径，不改变其回执语义。
- Storage Helper 对新命令仍校验真实 Unix peer UID。没有新增 Node 能力声明、正式
  用户 API、控制面授权签发或默认 Recorder 激活，未冒充完整进行中上传保全。

## 验证证据

客户端/契约 **8 + 5 = 13 passed**：新回执表覆盖1个合法与14个错误/不支持场景，检查
缺失 Proof、操作/Epoch/Recording/Sequence/大小/脱敏/Hash/版本数量错误及未知命令，
不出现第二次请求或普通上传 fallback；错误 Workspace 与 v1 请求在连接前拒绝。
同时检查旧回执没有新证明、Scope v1 序列化字节保持兼容。

2026-10-28 核验 OrbStack Running / context orbstack / OS=OrbStack 后，运行真实
Object Storage 专用 Gate：**8项库 + 2项真实 IPC = 10 passed / 0 failed / 0 skipped**。
新增 IPC 验收使用实际 Client→Unix Socket→StorageService→MinIO：

1. v2 隐私证明缺失时拒绝；绕过客户端直接发 v1 也被实际 Helper 拒绝。独立 S3 版本
   列表确认该 Session 前缀内连 AUTHORITY/对象都尚未产生。
2. 合法合成 v2 段成功上传，数据/Marker 的两个精确版本均原生 ON；客户端重放得到
   相同回执，原生版本数仍为2，staging 文件保留。
3. 同一录制通过普通上传命令尝试降级被实际 Helper 拒绝；模拟 allowed UID 不匹配时
   真实 peer credentials 检查拒绝，原生对象集合仍只有两个 ON 版本。

该 Gate 的像素及扫描证明为明确合成夹具，验证的是证明校验/IPC/存储链；没有实际运行
OCR 或真实 Recorder，也未启动不同 OS 用户的独立攻击进程，不扩张这些证据的范围。
全 Workspace/全部 targets 严格 Clippy、格式与修改脚本语法检查通过。

私有日志：/tmp/agentbrowser-upload-hold-ipc-client-tests-final.log、
/tmp/agentbrowser-upload-hold-ipc-clippy-20261028.log、
/tmp/agentbrowser-upload-hold-ipc-minio-20261028-v2.log。首轮在构建 mc 基础镜像时因
Docker Hub Alpine 拉取网络超时失败，尚未执行测试；随后显式使用已缓存的 node:20-alpine
作为 mc shell 容器基础镜像重跑，MinIO/mc 精确官方源码提交/Release 校验未变。
本地替代基础镜像不代表默认 CI 已通过；新提交仍须自己的完整流水线。

## 用户要求的资源清理

只读盘点并确认来源后，已删除3个2026-10-07的已停止集成容器、其3个附属匿名卷及
对应空网络；逐卷确认不再存在。当前测试结束后，专用 MinIO/Replica/网络正常清理，
另外删除本轮新构建且未被任何容器引用的两个 MinIO/mc 镜像标签。保留其他8个容器及
原有/共享基础镜像、构建缓存和用户文件，未使用全局 prune。

Integration、Object Storage GameDay、PG retention/order 与云保全 SQL Gate 的定向
cleanup 改为 docker rm -fv，仅作用各运行自己生成的名称，避免普通镜像声明的匿名卷
在容器删除后遗留。已通过实际 GameDay 收尾及 Shell/Python 语法检查。测试源码在运行
期间保持冻结，没有重犯 progress297 的动态修改问题。
清理报告：/tmp/agentbrowser-resource-cleanup-report-20261028.json。

## 前序精确 SHA CI 收尾与剩余范围

- 1865f77：主 CI40063405455、Desktop40063405460全部成功；读取 Verify 日志确认
  recording_cloud_hold_native_rejection_preserves_unknown=true 实际执行。
- fce3256：主 CI40064401619、Desktop40064401627全部成功；读取 Verify 日志确认
  新增5项 real_s3_upload_hold_* 均实际执行，及完整 Integration/GameDay 成功。

仍缺 PostgreSQL 权威授权/活动录制登记、Node/Recorder 启动与持久 ACK/有界 staging 清理、
最终 Manifest 的原子 ON、并发 writer seal/静默与所有权交接、最终 Hold 队列安全承接及
跨 Node/真实进程崩溃/晚到写入完整链故障 Gate。当前原语及 IPC 没有授予 OFF 或宣称
可删除活动录制。目标云、真实外部系统/客户 Replay、Linux/多 Region/组织与许可证等
原十一项目标范围继续保留；不满足 V16 生产发布条件。
