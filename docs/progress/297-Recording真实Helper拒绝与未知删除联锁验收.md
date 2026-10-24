# Recording 真实 Helper 拒绝与未知删除联锁验收

> 日期：2026-10-24
> 基线：db0d2fad313cd5d1077222281feb638ffcaff75b
> 状态：真实本机专用链通过；新提交完整 CI 待验证。

## 本轮关闭的验收缺口

在 progress296 的成功 ON/OFF/删除链后，新增另一份明确的 owned 录制夹具。其实际 S3
Manifest 保持完整，控制面初始投影使用格式合法但与实际内容不符的 Hash。测试随后只通过
正式管理员策略 API 创建 ON 请求，未写假回执或篡改 Worker 状态。

- 实际 Node/mTLS/Helper 路径触发 RECORDING_HOLD_MANIFEST_REJECTED；Helper 新增
  与现有 IPC 相同的有界原因码日志，不记录请求、对象位置、身份、凭据或原始 SDK Cause。
  Gate 比较该私有 Helper 日志码出现次数，避免仅凭控制面 UNKNOWN 推断 Helper 已拒绝。
- 正式 API 显示 UNKNOWN，一条活动不可变请求保持原 ON/业务 Epoch/错误 Manifest，
  无 applied/proof；JPA 真实追加 RECONCILE/UNKNOWN 审计，独立 S3 oracle 确认原 51 对象、
  52 版本均仍 OFF。平台阻止删除不等于云端原生 ON，测试显式区分这两者。
- 管理员改成期望 OFF 后，summary 仍 UNKNOWN；原活动 ON 请求保持不变，desired Epoch
  增为 2，不能用新 OFF 覆盖。直接关闭云管理仍返回 409/NOT_RELEASED。
- 仅推进测试录制的到期时间，运行真实删除扫描后，平台 Hold 仍保留，无删除 Job、
  deletedAt 或删除 Proof；Hold 请求仍唯一。独立回读确认全部版本仍存在且 OFF。

夹具只占独立测试 Tenant/Recording，结束后随 owned MinIO/PG 容器回收。它不代表真实
采集或客户数据，不将数据库 Hash 故障当成生产中已恢复的数据损坏。

## 可重复入口与结果

新增 make test-recording-native-hold，复用完整 Integration 的隔离 PostgreSQL/Redis/
MinIO、mTLS、实际 Node/Helper 启动和能力检查，只执行上述成功/拒绝两条链。
INTEGRATION_GATE_SCOPE 仅接受 full/recording-hold，默认 full；CI 的 make test-integration
仍执行所有业务域及两条保全链，没有换成专用测试。

OrbStack Running / context orbstack / OS=OrbStack 核验后，专用 Gate 退出 0，包含：

- recording_cloud_hold_native_mtls=true
- recording_cloud_hold_native_rejection_preserves_unknown=true
- recording_hold_focused_integration=true
- ON/OFF 的 51 对象/52 版本及成功链 EMPTY 的独立原生回读。

Rust storage-helper 全 targets 严格 Clippy、cargo fmt、三个 Shell 脚本 bash -n 通过。
私有日志 /tmp/agentbrowser-native-hold-rejection-focused.log、
/tmp/agentbrowser-hold-rejection-clippy.log，不提交凭据或原始运行日志。

## 测试运行完整性修正

首次完整本地尝试期间错误修改了正在读取的 Shell 文件，后段发生语法错误，且进程返回 0、
没有完成标记。因此 /tmp/agentbrowser-native-hold-rejection-integration.log 明确作废，
不算通过；最终专用 Gate 使用修改冻结后的脚本运行。

Integration 增加明确的完成状态：所有断言与末尾输出完成后才标记 true，专用模式也有
独立末尾标记。EXIT cleanup 保留原失败码，即使原退出为 0，未完成也强制失败；INT/TERM
分别保留 130/143。用实际 cleanup 函数及 owned 空目录/无网络 Docker stub 验证正常完成、
提前退出、原失败码和语法错误四种场景，均符合预期。该检查不使动态修改运行源成为安全操作，
后续测试仍必须冻结执行源。

## 已核验基线与剩余边界

已再次通过 GitHub 权威状态确认 db0d2fa 的主 CI 40061229407、Desktop 40061229433
精确 SHA completed/success；progress296 已更新实际扫描、Integration/GameDay 和全部 Job
收尾。本提交须自己的完整 CI。

此次只关闭完整链中的明确 Helper 拒绝与后续 UNKNOWN/删除联锁验收，未覆盖完整链
丢响应、进程崩溃、晚到 OFF 等故障；分层既有证据不升级成完整链证据。

进行中上传原生 Hold 仍缺完整产品路径，继续保留以下必要约束：

1. 首次上传前取得持久、精确租户/录制/Placement Epoch 的授权，并受当前策略约束。
2. 数据段、COMMITTED Marker 与最终 Manifest 的 create-only PUT 同时施加原生 ON。
3. 上传结果未知时保持原请求围栏，不能以重新上传或超时推断消除在途写入。
4. 最终保全只能承接有精确版本所有权证明的上传，不能把外部 ON 认作本系统可解除的 Hold。
5. OFF 前证明活动写入已终止；未知写入不得在 OFF/删除后迟到重建对象或改变已验证集合。

以上是后续实施要求，并非本轮已实现能力。跨 Node 重绑定、PREPARED 长期未知的远端
静默证明、目标云/真实供应商/客户 Replay/Linux 长稳/组织和许可证 Gate，以及原十一项目标
其余范围全部保留；当前仍不满足 V16 发布条件。
