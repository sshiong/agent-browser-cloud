# Recording 封口统一恢复入口与失败续行验证

> 日期：2026-10-29
> 基线：cacd11d56444ba3dab00bb1b0c58a737cd0288e0
> 状态：客户端恢复编排/故障单测通过；本轮真实 MinIO 与完整 Integration 待 CI 验证。

## 统一恢复入口

StorageHelperClient 新增 resume_recording_held_upload_seal，读取 Journal 中精确的
准备请求，先核对 Workspace 与完整 Scope，再复用有界 Helper 命令/证明校验，最后
提交不可变本地结果。返回值不包含对象路径。缺准备或旧 Placement/Scope 在连接前
拒绝；每次调用只有一次RPC，不清除原请求、不改变Epoch、不自动循环重试或降级。

Helper成功但本地提交失败时返回固定 LOCAL_COMMIT_UNKNOWN，调用者不能把远端
成功当成本地已完成。后续仍按原请求核对远端并幂等提交，不以当前环境重新组装请求。
Helper Client 新增 node-journal 依赖，锁文件同步；无新外部API或SDK漂移。

新增真实链中的恢复调用：远端已完成、尚未登记本地封口结果时，统一入口重新核对并
落盘。该分支本轮只完成编译/Clippy，因本机Docker daemon无响应，尚未本地执行，
不能将前一提交的18项MinIO结果套用到本轮新入口。

## 本地验证

- 客户端12、契约5、Journal16，共33项通过。新2项测试包含丢弃真实Unix Socket
  响应、错配Proof、以专用SQLite触发器阻止本地结果提交；失败后关闭重开Journal，
  原准备保持，第二次RPC命令与第一次精确相同，成功后读取结果一致，无隐式额外连接。
  缺准备和错Placement均在连接前拒绝。模拟服务端不是云端，不算真实MinIO故障证明。
- 首轮新单测因上传夹具段号4与单段封口连续0要求不符而失败，已仅修正新夹具序号0；
  完整33项重跑成功，未放宽产品校验。
- 全Workspace/全部targets严格Clippy、格式/diff通过。
- 日志 /tmp/agentbrowser-seal-resume-unit-final.log、
  /tmp/agentbrowser-seal-resume-clippy.log。

## CI 失败与 Integration 修正

242bc32主CI40719477515、Desktop40719477473精确SHA全成功，Verify日志确认封口
Journal竞争、真实IPC及UNKNOWN原生拒绝标记实际执行，progress303已收尾。

cacd11d主CI40721913323的Verify在Integration失败，Operator成功；失败请求为假成功
Outcome完成。SQLState40P01，等待tenant_audit_heads形成死锁；API按既有契约返回
503 DATABASE_TRANSACTION_RETRY。测试原来curl -f导致空输出/JSONDecodeError，
未执行后续假成功拒绝断言。此次未修复服务端锁顺序或宣称消除死锁。

仅该测试增加最多5次、相同原始请求体的有界重试，只接受503且code精确为
DATABASE_TRANSACTION_RETRY、details.retryable严格为布尔true。网络错误、其他
503、409或错误200均立即失败；成功后原NOT_VERIFIED/EXPECTED_OUTCOME_NOT_MET
和Task/Job持久状态断言保留。依据是服务端40P01/40001事务已回滚，不是重放未知
业务副作用。每次curl最多15秒，不扩展为通用API自动重试。

新增3项无服务的回归测试，执行smoke.sh精确片段，以自有curl stub证明：有效冲突后
成功、5次耗尽、其他失败/伪成功不重试、每次请求体不变。make test-integration先执行
这些用例再执行完整smoke，未删Gate或绕过主流程。3项与bash语法通过，完整产品链
修正仍待新CI。死锁锁顺序分析仍为后续产品稳定性工作。

cacd11d Desktop40721913209中Windows成功、macOS取消，整体失败；没有把取消视为
通过。本提交6f4fc76主CI40726646167及Desktop40726646122精确SHA所有Jobs成功，
含Operator/Windows/macOS；Verify日志确认新失败续行单测、真实封口IPC、原生
UNKNOWN拒绝及Integration3项独立回归执行。统一恢复入口的真实链后续已验证，
不再仅停留本机daemon故障时的编译证据。私有日志
/tmp/agentbrowser-seal-request-ci-failed.log、/tmp/agentbrowser-seal-request-macos.log。

## 资源与剩余

OrbStack报告Running、context为orbstack，但docker info持续无响应，原生Unix
socket /_ping也在5秒超时。orbctl start返回已运行。本轮未启动MinIO/Integration，
未创建测试容器/镜像/网络；已取消本任务两个挂起的只读docker info进程，未重启共享
运行时、切换Desktop或对原有服务作删除/prune。前轮已清理的资源不会被重复计为本轮。

正式Recorder/Node接入、PG活动录制/权威准入、自动恢复调度及预算、生产者停止并await、
跨Node、Seal→Hold安全交接、旧非感知写入器资格/Placement围栏和目标云Gate仍缺。
本轮统一入口仍由调用者显式调用，不会启动扫描器；不能称默认录制已支持上传保全。
原十一项客户Replay、真实外部系统、Linux/Region、组织和许可证Gate继续保留。
