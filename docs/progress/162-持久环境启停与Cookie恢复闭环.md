# 持久环境启停与 Cookie 恢复

> 日期：2026-09-02
> 状态：本地实现、完整回归、真实 Chromium/OrbStack 验收通过；GitHub 待推送检查。

## 用户确认的语义

- 启动：打开此环境的浏览器，恢复最新 Profile Checkpoint，不重新创建环境。
- 停止：关闭浏览器，保存 Cookie、登录资料和 Profile，保留环境以便下次启动。
- 删除：从环境列表移除；审计/录制/恢复证据按既有保留策略处理，并非立即物理擦除所有资料。

## 实现

- 新增 Tenant/RBAC、物理 Coordinator Owner 路由的 `POST /sessions/{id}:stop`，202 返回
  持久 Operation，复用 HIBERNATE_RUNTIME/Outbox/Node Journal/StopRuntime/Checkpoint。
  显式操作员停止可中断活跃启动/任务；自动资源休眠仍保持原有安全点/不可抢占限制。
- StopRuntime 成功后为 HIBERNATED；保存失败不能伪装成成功。启动仍使用最新持久 Checkpoint；
  CREATED/HIBERNATED/未删除的历史 TERMINATED 均可显式启动。软删除对象保持不可见/不可启动。
- Browser.close 给 Chromium 正常落盘机会，再保存 Profile；超时有界强制清理并记录 Warning。
  `--restore-last-session` 恢复会话 Cookie。网站主动撤销授权/过期登录、强制清理未落盘数据
  不能保证恢复；停止会中断当前任务，而不是承诺自动恢复同一任务。
- 总览/环境列表/详情使用 `:stop`；“停止中/已停止”、启动入口、红色停止方框及确认说明共用
  Web/Tauri 逻辑。批量软删除现在允许 HIBERNATED 且没有 ACTIVE Operation 的环境。
- OpenAPI/四 SDK/生成 Manifest 同步为 240 Operations / 319 Schemas；内部协议无破坏性变更。
- OrbStack 镜像显式安装 chromium-sandbox，Browser Node 使用 Moby v28.3.3 默认 seccomp
  加 clone/setns/unshare，仍非 root，无 privileged/SYS_ADMIN/--no-sandbox。
  来源与边界见 `deploy/docker/README.md`。

## 可重复验证

- Control Plane 492 项、Web 132 项、Rust Workspace/Clippy、Worker/Provider，完整
  `make test lint build` 通过；最后仅增加真实 Checkpoint 测试依赖后重新通过 Rust 全量/Clippy。
- `make contracts-check test-sdk test-upgrade-compatibility`，Desktop test/lint/unsigned build、
  供应链、Operator 和 50k Coordinator Capacity 通过。
- 完整 `make test-integration` 通过，新增 `reusable_session_lifecycle=true`：同 Session
  TERMINATED→RUNNING→HIBERNATED→RUNNING→HIBERNATED，Profile 启动标记从4递增到6，
  Viewer/跨租户 stop 均403。这里 PostgreSQL/Redis/MinIO/mTLS/Node/Storage 为真实进程，
  Chromium 为确定性 fixture，不冒充真实浏览器 Cookie 验收。
- 单独真实 Chrome 测试 `starts_probes_and_stops_real_chromium -- --ignored`：写入测试用
  持久 Cookie 和会话 Cookie，正常关闭并创建真实 LocalProfileStore Checkpoint，移除测试
  工作目录后同 Tenant/Profile/Session 重获工作区，验证从指定 Checkpoint 恢复且两类 Cookie 均存在。
- OrbStack 独立 Fixture `ses_210af35de574450d` 在页面启动后真实 RUNNING，停止后
  HIBERNATED（151个 Profile 文件、约2.2MB Checkpoint），同一 ID 再启动达到 RUNNING、
  browserGeneration=2。页面状态由 SSE 自动同步；未更改原有环境。
- 总览再次停止后，通过环境列表复选框直接删除 HIBERNATED Fixture，GET 返回404；仅删除
  自建测试记录，审计/Profile 保留。Web 重建瞬间一次请求断线显示失败，重试后正常完成。
- 既有大型 `tests/e2e/web_console_session_flow.mjs` 的启停断言已更新并通过语法检查，
  本轮未重跑该完整 E2E；以上实际页面验收与完整 Integration 分开报告。
- 容器内 Maven 构建遇上游 TLS 下载失败；本次本地部署使用已通过构建/测试的同版 Boot JAR
  制作等价非 root 运行镜像。Node/Web 源码镜像构建成功，不能把 Maven 源码镜像构建记为通过。

## 剩余边界

本切片不等于 V16 生产发布，不替代目标 Linux 沙箱长稳、云对象存储/跨 Region 和真实 IdP 验收。
历史 progress 161 的“不可重启/沙箱阻塞”仅描述上一切片，当前由本文件取代。
