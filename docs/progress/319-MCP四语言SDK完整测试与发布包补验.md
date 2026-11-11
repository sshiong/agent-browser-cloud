# MCP 四语言 SDK 完整测试与发布包补验

> 日期：2026-11-11；基线e9f59deb9addb068d46f2c51f82e74d29290b983

最新主CI42848389589在Verify的Python SDK测试失败：旧255操作数断言未随MCP
契约268操作更新。Desktop42848389320双平台成功，Operator成功；不能将此提交
计为主CI通过，MCP专用集成步骤尚未执行。

同步Python/Go/Java三处旧操作数基线；新增Python运行时MCP请求测试，验证创建
使用agent-access路径、Bearer连接凭据与幂等键，启动路径、列表分页参数和DELETE
方法正确，凭据不进入URL。不是仅放宽断言，原Session请求与错误处理测试保留。
Go和Java生成源码未修改，仅更新已有测试覆盖基线；正式契约268/368未变化。

make test-sdk完整通过：Python、TypeScript、独立npm包、Go、Java客户端测试与
实际四语言发布包构建全部通过，8项发布产物及精确MIT LICENSE校验通过。
首轮Python修复后Go旧基线暴露并失败，三语言同步后完整重跑成功。
此前生成Manifest校验通过不能替代这些运行时测试，现补齐该证据。

本轮未创建Docker资源。最新提交仍须独立CI验证；首版静态Bearer MCP生命周期
能力见progress317—318，OAuth专用平台适配及目标K8s域名/TLS验收仍保留。
