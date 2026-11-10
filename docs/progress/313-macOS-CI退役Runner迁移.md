# macOS CI 退役 Runner 迁移

> 日期：2026-11-10
> 基线：f4cc2ec9fc578555b8e86bbccd2bde2dcc4a10a2

Desktop42772209705的macOS Job129682413851在执行任何步骤前被取消。
Actions Job API指向同ID check-run，读取其annotations得到明确失败原因：
The configuration 'macos-14-arm64-us-default' is not supported。
22cc7d6的Desktop42771531429也出现相同启动取消现象，不能以Windows结果代替macOS。

GitHub官方runner-images说明macOS14全部镜像已于2026-11-02从Actions退役，
macos-15为支持的Arm64标准标签：
https://github.com/actions/runner-images#available-images

因此将Desktop矩阵macos-14改为明确macos-15，保留Windows2022、原生测试、完整
unsigned构建、最小权限与30分钟超时。未使用latest浮动标签，也未跳过构建。
本次仅修改runner选择，不声明macOS14运行兼容性经过新验证。YAML解析与文档检查
本机通过；新runner是否实际执行并成功须等待当前提交的Desktop CI。

基线主CI42772209599和前一主CI42771531383仍运行；不撤销正在提供证据的既有任务。
本轮没有Docker操作或测试资源。原十一项剩余上传准入、交接、跨Node、真实外部系统、
目标环境长稳与组织Gate继续保留。
