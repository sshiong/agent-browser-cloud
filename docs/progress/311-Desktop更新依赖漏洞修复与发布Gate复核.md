# Desktop 更新依赖漏洞修复与发布 Gate 复核

> 日期：2026-11-10
> 基线：7d9db9a786cbce239a56dc70deb91be13ec438f9

精确SHA主CI40836953863在Scan source and dependencies失败：Desktop Updater间接
引入zip4.6.1，扫描报告CVE-2026-115905 CRITICAL；后续Integration、新录制投影和
真实Chrome步骤未运行。Operator成功，Desktop40836953873的Windows/macOS成功。
因此不能将该提交标记为主CI通过。原progress309本机证据仍成立。

升级稳定tauri-plugin-updater2.14.1，按其依赖配套tauri2.12.5和tauri-build2.7.4，
锁文件zip升级9.5.0。2.11.0/2.13.3仍依赖zip4，未采用；仅调整Updater会与固定
Tauri版本冲突，故保留完整配套解析结果。未添加扫描例外，也未禁用更新或签名校验。
上游公告：https://github.com/zip-rs/zip2/security/advisories/GHSA-m665-v7pm-7grj

本机make test-desktop lint-desktop退出0，原生单元测试、格式与cargo check通过。
本机没有Trivy可执行文件，修复版本的最终漏洞扫描与Windows/macOS构建须由新提交
CI确认；未宣称全仓依赖无漏洞。本轮未执行Docker命令或创建容器/镜像/卷。
原十一项目标其余外部系统、目标云、长稳和组织Gate继续保留。
