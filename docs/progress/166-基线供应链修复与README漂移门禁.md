# 基线供应链修复与 README 漂移门禁

日期：2026-09-03；承接 progress 165。两项独立变更，不改变正式 API/Protobuf。

## 基线供应链失败

读取实际 GitHub 记录发现 `917a8ff` 的 `ci` run `33713135290` 为 failure，
`desktop` run `33713135379` 为 success。前一实现 `2ecbfda` 也是 ci failure / desktop success；
旧文档的“检查时运行中”不能作为绿色证据。

失败步骤为 Trivy，Terraform Provider 的 `google.golang.org/grpc v1.82.1` 命中
`CVE-2026-84304`。上游公告说明 HTTP/2 小 DATA frame 的缓冲放大会耗尽堆内存，
修复版本为 1.83.1。此次精确升级至 1.83.1，并由 `go mod tidy` 更新必要的传递依赖和校验和，
不添加扫描忽略规则、不降低 HIGH/CRITICAL 门禁。

验证：Provider `test -race ./...`、`vet ./...`、真实二进制 build 和发布门禁通过。
GitHub 新提交的 Trivy 与全量 CI 仍须单独确认。

来源：[gRPC-Go 官方安全公告](https://github.com/grpc/grpc-go/security/advisories/GHSA-vp52-pcj8-j9qc)、
[1.83.1 发布说明](https://github.com/grpc/grpc-go/releases/tag/v1.83.1)。

## A23：README 目录漂移

移除已不存在的 `apps/cli`、`packages/policy-schemas` 等旧目录叙述。模块表由
`tools/docs/check_readme.py` 基于 `git ls-files -z` 生成，因此本机未跟踪备份、嵌套仓库和
构建目录不会混入。新增/删除模块应先暂存，再执行 `make docs-generate`。

`make docs-check` 已纳入 `make ci`：比较模块表、拒绝重复/颠倒 marker、检查 README 的
本地 Markdown 链接。5 项测试覆盖排序/去重、模块增删、幂等生成、异常 marker、链接失效。
README 顶部不再复制易漂移的固定镜像数量/历史完成状态，改链接到权威进度与剩余清单，
且明确默认 Compose 的 Worker 缺口与公网禁用边界。

边界：本检查不证明所有历史进度文档的语义正确，也不替代代码/API/运行证据审查。
