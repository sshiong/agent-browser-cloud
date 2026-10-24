# Recording 原生保全完整链验收与 Zod 升级

> 日期：2026-10-24
> 基线：2b231664c102934af54531883d971a38b3d24431
> 状态：本机与干净 Linux CI 完整链通过，精确 SHA 主 CI/Desktop 全成功。

## 真实执行与独立回读

Integration 新增独立 Tenant 的正式策略 API → PostgreSQL/JPA 审计 → 调度 Worker →
正式双向 TLS Gateway → 实际 Rust Node → UID 保护的 Helper IPC → 实际 MinIO 链路。
默认关闭的云标记由管理员 API 开启，未通过 SQL 伪造期望、租约、回执或成功状态。

- 合成 25 段录制形成 51 个对象，额外创建一份内容相同的历史版本，总计 52 版本。
- ON 保存返回 PENDING，Worker 完成后正式 GET 返回 VERIFIED ON。
- 独立只读 S3 oracle 逐版本 GetObjectLegalHold 验证全部 52 版本 ON；没有读取
  Executor 的成功判断来充当原生状态证据。弱角色写入被正式 API 拒绝为 403。
- ON 尚未解除时直接关闭云管理返回 409；保持管理的 OFF 请求完成后，oracle 逐版本
  验证 OFF，数据库核对不可变请求的前序 Proof 链、递增业务 Epoch 与版本数。
- JPA 审计真实持久化两次 APPLY_ON/APPLY_OFF 成功，随后才允许关闭管理。
- 仅推进本测试录制的到期时间，由真实删除 Worker 执行；回执确认 51 个对象，独立
  ListObjectVersions 确认所有 52 版本和 Delete Marker 均不存在。

Oracle 仅允许固定 owned Tenant/Recording 前缀和 literal loopback 端点，凭据来自环境，
只读且有 30 秒总预算。MinIO 的 OFF 缺省元数据只在 Bucket Object Lock 已开启且
精确 Version HEAD 存在时接受，不将任意 404 当成 OFF。错误输出固定码，不输出对象位置。
原有 fixture 默认时间与单版本行为保持兼容；新历史版本和当前时间由显式测试开关启用。

## 本机证据

OrbStack Running / context orbstack / OS=OrbStack 核验后，完整 make test-integration
退出 0，输出 ON objects=51 versions=52、OFF objects=51 versions=52、EMPTY objects=0
versions=0、recording_cloud_hold_native_mtls=true、audit_chain_valid=true。
首次新 Gate 因测试误把创建响应当成包含 profileId 的详情响应而失败，改为正式 GET 详情后
重跑全链通过，没有降低产品校验。Rust examples cargo check、严格 Clippy、格式通过。
默认 Java bootJar 已实际重新 generateProto 成功，不依赖跳过生成。

私有日志：/tmp/agentbrowser-native-hold-integration-v2.log（exit 0）、
/tmp/agentbrowser-native-hold-examples-check.log、/tmp/agentbrowser-native-hold-examples-clippy.log、
/tmp/agentbrowser-native-hold-bootjar.log。日志不提交仓库。

## 依赖扫描与兼容性

基线 2b23166 的主 CI 40056644352 在依赖扫描报告 zod 3.25.76 的 HIGH
CVE-2023-54404，后续 PostgreSQL/Integration/GameDay 未执行；Desktop 40056644390
成功。不能把该主流水线称为全绿。

升级 Zod 至锁文件解析的 4.6.7、兼容 resolver 5.2.2，未忽略漏洞或降低扫描门槛。
将创建环境表单的既有 schema 提取到独立模块，useForm 显式区分 DOM 原始输入与校验后
输出；保留数字 coercion、空成本、严格终止确认与 Profile 必填语义。
新增真实 resolver 回归覆盖数字字符串、字段错误、严格确认，以及超长数组在读取任一
元素前拒绝（元素 getter 若被读取会直接抛错），避免只检查版本号。

前端 153 项 Vitest、noVNC 检查、TypeScript/Vite 构建与 ESLint 通过。私有日志：
/tmp/agentbrowser-zod-tests.log、/tmp/agentbrowser-zod-build-final.log、
/tmp/agentbrowser-zod-lint.log。本机未安装 Trivy；db0d2fa 的 CI 扫描已实际通过。

上游依据：[Zod issue](https://github.com/colinhacks/zod/issues/6643)、
[4.6.7 release](https://github.com/colinhacks/zod/releases/tag/v4.6.7)、
[Zod 4 migration](https://zod.dev/v4/changelog)、
[resolver 5.2.2](https://github.com/react-hook-form/resolvers/releases/tag/v5.2.2)。

## 保留边界

录制字节与初始 Node/Manifest 元数据为明确测试夹具，Session 未启动真实录制。
因此这次证明正式策略之后的真实执行与审计链，不证明生产采集/Node Event 生产者或进行中
上传已经受原生 Hold。一般 Integration 浏览器夹具也不能当成真实 Chrome 内容证据。
未知/坏回执/晚到 OFF 有既有分层测试，本次没有在完整链注入故障，不替代该项验收。

进行中上传原生保全、跨 Node 重绑定、PREPARED 长期未知的远端静默证明、目标账户云端
IAM/Object Lock/跨 Region/长稳仍未完成。原十一项其余外部系统、客户 Replay、Linux
规模证据、许可证权利人决定和组织 Gate 保留；不作 V16 发布或客户数据处理结论。

## 精确提交 CI 收尾

db0d2fad313cd5d1077222281feb638ffcaff75b 的主 CI **40061229407**、
Desktop **40061229433** 均 completed/success。Verify、Build、扫描、PostgreSQL
专用 Gate、完整 Integration、对象存储/Recording GameDay、Operator 与 Windows/macOS
全部成功。已读取 Verify Job 日志，确认新 ON/OFF 各 52 版本、EMPTY 与
recording_cloud_hold_native_mtls=true 标记实际执行，未以 Job 名称代替覆盖证据。
