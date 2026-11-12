# Oracle ARM 核心部署与镜像缺陷修复

> 日期：2026-11-12；部署基线5304048，加本轮Dockerfile修复；尚非全功能验收。

按用户要求通过Termius oracle-sg-arm实际SSH操作，在独立目录
/opt/agent-browser-cloud部署，Compose项目agentbrowser-sg。主机ARM64/4核/23GiB，
初始磁盘剩余约51GiB。安装Ubuntu源docker-compose-v2 2.40.3，保留原4个业务容器，
不改防火墙、不开放公网端口、不重启主机/共享Docker。随机数据库与票据配置仅在
服务器.env中，权限0600；模型Key未提供，用户明确先部署。

真实构建发现Web Dockerfile在pnpm install前遗漏noVNC补丁目录，导致ENOENT。
增加COPY patches并重建成功；CI增加实际Web镜像构建，避免源码构建通过但镜像失败。
私有umask077克隆使MCP源码只允许root读，COPY保留权限，UID65532无法打开gateway.py。
MCP Dockerfile显式COPY --chmod=0444；同类Agent Worker入口文件统一0444，保留非root。
修复后所有Compose应用镜像原生ARM构建完成，Node隐私扫描器Tesseract/OpenCV自检成功。

已运行7服务：postgres、redis、browser-node、storage-helper、control-plane、web-console、
mcp-gateway。控制面/PG/Redis/MCP健康，Web实际HTTP200，MCP healthz UP。仅loopback
绑定3000/8080/8130及数据库端口。开发Local模式只能通过SSH隧道使用，不能作为公网模板。
通过Termius本地转发3000→远端127.0.0.1:3000访问Web；MCP另转发8130。

官方MCP客户端在实际Gateway容器内验证9工具发现、连接认证、配额读取和环境创建。
启动两次均503 BROWSER_CAPACITY_UNAVAILABLE，未通过真实Chromium启停链。
真实Node心跳不是虚构bootstrap；检查发现宿主机PSI I/O Full约19%、Memory Full约3.59%，
权威节点状态CRITICAL/CLOSED。未禁用保护或写假压力。节点部署覆盖配置
oracle-sg.override.json按主机保守设置4000 CPU millis/8192MiB/最多2环境，未作为生产容量证书。
当前仅查看资源指标，未修改共享业务；压力来源与恢复仍待排查。两测试环境按正式API软删除，
所有同名测试连接撤销。原4业务容器运行时长保留，新7服务保持运行，无全局prune。

模型四Worker镜像已构建但未启动：缺真实模型配置与Key，不使用假凭据。
默认本地Compose OBJECT_STORAGE_ENABLED=false，尚未部署/验证Recording、对象存储、
Profile云归档与企业OIDC等全功能链。当前结果只算核心部署与部分MCP验收，不能写全功能完成。
服务器私有构建/启动/测试日志位于/root/agentbrowser-*，不进入仓库。新修复CI待验。
