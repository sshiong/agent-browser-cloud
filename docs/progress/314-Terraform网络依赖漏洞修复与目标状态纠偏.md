# Terraform 网络依赖漏洞修复与目标状态纠偏

> 日期：2026-11-10
> 基线：e4aa6b86b3cf8329434623dc82cf654fd444c654

前置提交22cc7d6主CI42771531383最终在Scan source and dependencies失败：
Terraform Provider间接依赖golang.org/x/net v0.60.0命中CVE-2026-78661 HIGH，
修复版本v0.62.0。该扫描报告未再列出之前的zip漏洞，但不等于全CI成功，后续
Integration/录制专用/真实Chrome未执行。Operator成功。

升级Provider的x/net至v0.62.0，并接受Go最小版本选择带来的x/sys v0.49.0与
x/text v0.43.0；go mod tidy同步锁定校验。make test-terraform-provider的-race
客户端与Provider用例通过，go vet ./...通过。未增加扫描例外。本机无Trivy，
新提交最终漏洞扫描仍待CI验证。

同时纠正AGENTS十一项目标总表第5项：此前标注仓库链已闭环范围过宽。当前应为
部分完成；既有播放/删除/扫描与组件测试证据成立，但PG上传准入、默认Node
upload_hold=None启用、Seal→Hold交接、旧写入器围栏、跨Node仍缺，不能以组件
测试替代产品全链认证。目标云和客户视觉类别验证同样保留。

最新e4aa6b8的Desktop42772447688已在macOS15和Windows实际通过原生测试、
进入unsigned构建，最终结果仍待核验。本轮未执行Docker命令或创建容器资源。
原十一项剩余外部系统、目标环境和组织Gate继续保留。
