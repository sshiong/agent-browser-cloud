# Recording 上传时原生 ON 与逐对象未知围栏原语

> 日期：2026-10-24
> 基线：1865f775538d42b531ecee96d27a31006d4a3862
> 状态：ObjectArchive 组件已实现并通过真实 MinIO；完整产品链未接入，不声明上传保全已完成。

## 实际代码

storage-helper ObjectArchive 新增 commit_recording_held_segment。调用方提交精确
Tenant/Profile/Session/Recording、操作 ID、authority/placement Epoch 的 Scope；先在
Recording 删除目录外写不可变 AUTHORITY，再分别持久化数据和 Marker 的逐对象 PREPARED。
Scope 与 Bucket/Prefix 绑定，操作、Epoch、Placement 或载荷不同均不能覆盖原记录。

该 Scope 是可信运行时内部原语的输入，**当前没有控制面授权签发/验证链**，不能把调用方
填写 Epoch 视为已获得正式授权。既有 Node/Recorder 没有调用新接口，也未声明新能力。

- 上传前确认 Bucket 已开启 Object Lock；数据段与逐段 COMMITTED Marker 均在第一次
  create-only PutObject 中同时指定原生 ON，未用“上传后再补 ON”代替原子保护。
- 每次实际对象 PUT 禁用 SDK 重试；只有成功创建该对象 PREPARED 的调用可派发一次。
  已有意图后所有重试仅回读，不能因对象暂未出现而重新上传。崩溃发生在意图提交后、
  派发前时也保持未知，不把“可能没有发出”当成可以重发的证明。
- 回读绑定随机 Nonce、Scope Hash、载荷 Hash、大小、精确非空 Version ID、原生 ON
  和完整版本字节；成功后写不可变逐版本 COMMITTED Proof。Proof 只证明数据/Marker 的
  精确版本，不宣称完整录制集合受保护，也不授予 OFF。
- 对象 PUT 超时/失败、回读缺失或冲突保留意图；公开 Rust 方法仅返回固定错误码，
  不透出对象坐标、底层 SDK Cause 或凭据。Journal 每份至多16KiB，单段至多16MiB，
  原语总预算60秒，原有每操作超时继续生效。
- 观察到上传 AUTHORITY 后，旧数据提交和旧最终化路径拒绝降级。新上传也拒绝已经
  可见的最终 Manifest、删除或最终 Hold 意图。这些是前置检查，**不是并发 writer seal**；
  未来交接仍必须证明在途写入已静默，不能由这些检查推断可以解除或删除。

新接口不写最终 Manifest、不调用 OFF、不承接外部已有 ON。旧最终 Hold 执行器仍要求
初始对象状态为 OFF，不能自动解除新上传保全；没有静默/所有权交接证明前保持不可解除。
普通未启用该原语的录制沿旧链上传，每次数据/最终提交增加一次 AUTHORITY 存在性检查；
检查不可达时拒绝，不冒险降级。

## 真实验证

OrbStack Running / context orbstack / OS=OrbStack 核验后，执行：

    OBJECT_STORAGE_TEST_FOCUS=legal-hold bash tests/failure-injection/object-storage-timeout.sh

最终 **8项库测试 + 1项真实 IPC = 9 passed / 0 failed / 0 skipped**。其中新增5项：

1. 最终 Manifest 尚不存在时，数据/Marker 两个精确版本均原生 ON，删除版本被 MinIO
   拒绝。新 Archive 实例重放返回相同 Proof，不增加版本；变更 Placement、authority
   Epoch、载荷均拒绝。旧上传与旧最终化也拒绝该受保护录制。
2. 两个控制器并发同一对象，只产生一个数据版本及一个 Marker 版本，成功 Proof 一致。
3. 外部原有 ON 版本不会被覆盖或承接，两次调用均拒绝，原版本仍 ON。
4. 显式预置 PREPARED、未派发任何对象 PUT，两个新实例重试均拒绝；原生对象列表仍空，
   未写 COMMITTED。测试的是崩溃窗口状态，未声称真实进程在该指令处被杀。
5. 显式预置 PREPARED 并执行真实带 ON 的对象 PUT，但不保存回执。新实例仅核对原
   版本后恢复 Proof，数据 Version ID 完全相同，补齐 Marker 后只有两个对象版本。
   这是丢回执状态模拟，未声称网络代理实际丢弃了响应。

既有并发 CAS、晚到 OFF、版本 ON/OFF/Fencing 与真实 UID IPC 亦通过。测试名均含
real_s3_，因此既有完整 GameDay 会实际执行，不是只留在默认 ignored 集合。
严格 Clippy --all-targets、cargo fmt 与 Git whitespace 检查通过。

私有日志 /tmp/agentbrowser-upload-hold-minio-final.log、
/tmp/agentbrowser-upload-hold-clippy-final.log；不提交对象位置/凭据或原始日志。
第一轮四项新测试先通过，随后为旧链降级围栏与第五项回执恢复进行了最终重跑；以上统计
取最终运行，不合并不同源码的部分结果。

## 尚未完成，继续实施

1. PostgreSQL 权威上传授权、策略变更与活动录制登记，受 Placement/租户/用途绑定。
2. 正式 Node/Helper IPC 与 Recorder 启动接入，继续先执行现有隐私扫描，不允许未经
   检测的像素借新原语上传；控制面未接入前不开放用户开关或宣称生产能力。
3. 最终 Manifest 的原子 ON、所有对象版本的所有权核验与关闭写入证明，以及最终 Hold
   队列的安全承接。不能将新原语的单段 Proof 当成完整 Recording Proof。
4. 跨 Node、崩溃/晚到写入/未知超时的完整产品链故障注入、目标云 IAM/Object Lock/
   Legal Hold/长稳。原十一项其余外部供应商、客户 Replay、Linux/Region/组织和许可证
   Gate 全部保留。当前不满足 V16 生产发布条件。
