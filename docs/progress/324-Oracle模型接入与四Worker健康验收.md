# Oracle 模型接入与四 Worker 健康验收

> 日期：2026-11-12；部署代码基线5304048加progress323镜像修复。

用户提供供应商地址和Key并指定6-luna。Key以隐藏输入写入服务器私有文件，0600，
不放Compose环境、不写仓库或普通日志。供应商/v1/models认证成功，公布的完整模型名为
 gpt-6-luna；短名6-luna返回model_not_found。改用正式名称后/v1/responses实际POST
HTTP200，包含output。测试仅发送无敏感信息的短提示，不将模型列表当作调用成功。

更新服务器私有.env中的模型端点/名称和文件引用，重建控制面运行配置，启动Agent、
Reviewer、Outcome Verifier、Vision四个独立Worker。实际Compose观察四个均running/healthy；
仓库verify-agent-compose.sh检查通过，包括健康状态和模型凭据不在容器环境。
当前11服务运行，原4个业务容器未变动。Worker健康是首次队列连接证据，不能当作
真实Task/Reviewer/Vision业务请求均已运行的证明；模型单独请求成功也不能替代完整任务链。

节点压力复查Memory Full已为0，I/O Full约1.9，仍DEGRADED/CLOSED，浏览器准入尚未恢复。
未禁用PSI门禁。真实Chromium启停、完整模型Agent任务、对象存储/Recording和OIDC仍未
完成本机验收。当前仅Loopback/SSH使用，生产Gate继续保留。供应商地址、Key及响应正文
不进入此文档，私有服务器配置是运行时权威，更新代码时不要覆盖.env和.local目录。
