# AUTO 截图证据与采样执行闭环

> 完成日期：2026-07-29
> 状态：仓库内真实 CDP 截图、Storage Helper 隔离提交、Node ACK、PostgreSQL/API/Web 与滚动升级 Gate 已闭环；完整集成等待 GitHub CI 最终确认

## 关闭的缺口

此前 Agent 动作没有独立截图证据链，“达到资源上限时降低截图频率”也没有 Browser
Node 执行器。前端若自行生成曲线或把未上传的本地文件显示成证据，会违反生产真实数据
要求。

本轮完成：

1. Browser Node 在 Agent 导航和交互动作完成后，通过独立回环 CDP
   `Page.captureScreenshot` 采集 JPEG，不依赖 Observer 或 Pixel Recording；
2. 成功动作使用 Message ID 的稳定哈希按 Node 权威百分比采样；失败动作和失败导航
   始终尝试留证，不进入成功采样；
3. 正常值为 100%，达到最大资源上限的非核心降载将成功截图采样调整为 10%；
4. 调整通过既有 `RESOURCE_ADJUSTMENT` Operation 下发，Node 返回旧值/新值 ACK，
   Control Plane 校验 Placement 旧值后才提交 PostgreSQL 和 Resource Event；
5. 截图最大 8 MiB，只能写入当前 Session 的
   `ephemeral/evidence/{evidenceId}/screenshot.jpeg`；
6. 只有 Storage Helper 持有 Object Storage 凭据。Helper 重新推导路径并验证普通文件、
   无符号链接、JPEG 大小、SHA-256、Evidence 类型和时间，再先上传像素对象、最后上传
   `COMMITTED` 标记；
7. Node 发布 `SessionEvidenceCaptured`。对象提交失败时发布 `FAILED` 元数据和稳定错误
   码，不会把缺失对象伪装成成功；
8. Control Plane 在 Node Event Inbox 同一事务中去重并写入 `session_evidence`，查询按
   Tenant + Session 隔离；
9. `GET /api/v1/sessions/{id}/evidence` 只返回证据 ID、类型、Task/Step/Command、结果、
   哈希、大小、时间和错误码，不公开内部 Object Key；
10. Session Detail 新增证据卡，明确区分已提交、留证失败、强制留证和暂无事件；Web 不
    生成截图、不模拟 CPU/内存或资源调整。

## 数据与安全边界

对象键由 Storage Helper 生成：

```text
tenants/{tenantId}/profiles/{profileId}/sessions/{sessionId}/
evidence/{evidenceId}/screenshot.jpeg
```

提交顺序为：

```text
Node CDP capture
→ bounded local file + fsync
→ Helper path/SHA/size validation
→ screenshot.jpeg
→ COMMITTED
→ SessionEvidenceCaptured(COMMITTED)
```

截图或对象存储失败时走：

```text
SessionEvidenceCaptured(FAILED, stable error code)
```

失败证据事件不会令已经完成的 Agent 动作回滚，但缺失像素会被真实记录并展示。原始像素
下载尚未开放，因此 API 不返回对象坐标，也没有临时 URL。

## Operation 与滚动升级

V049 增加：

- `browser_placements.success_screenshot_sample_percent`，安全默认 100；
- `session_evidence` 租户隔离元数据表；
- `NOT VALID → VALIDATE` 的百分比约束。

Protobuf 使用新增且不复用的字段号：

- `StartRuntimeCommand.success_screenshot_sample_percent = 29`
- `AdjustRuntimeResourcesCommand.success_screenshot_sample_percent = 24`
- `RuntimeResourcesAdjustedEvent.old/new_success_screenshot_sample_percent = 37/38`
- `SessionEvidenceCapturedEvent = 1..13`

新 Control Plane 收到不含截图字段的 N−1 ACK 时保持 Placement 原值；旧 Node 忽略新
命令字段。V049 不删除、重命名或改变既有列。

## API 与 Web

- Browser Placement 和 Session Resource Allocation 新增
  `successScreenshotSamplePercent`；
- Resource Event old/new resources 记录同一 Node ACK 值；
- `GET /api/v1/sessions/{id}/evidence?limit=20&offset=0` 返回持久元数据；
- Resource Panel 显示“成功截图 Node 采样 N%”并说明失败动作与导航始终留证；
- Session Evidence Card 展示最近六条真实事件，失败时显示稳定错误码。

Web 与未来 Tauri 2 共用同一 API Client、React Query Hook、类型和业务组件。

## 已完成验收

- Java 全量单测通过；Mapper 覆盖合法 COMMITTED 与无对象假成功拒绝，Inbox 覆盖证据
  持久服务调用；
- Rust Workspace 全量单测通过；真实 HTTP `/json/list` + WebSocket 测试验证
  `Page.enable → Page.captureScreenshot(format=jpeg, quality=70)` 和 JPEG 字节；
- Web lint、13 个测试文件/43 项测试和 production build 通过；
- OpenAPI/Buf 校验通过；
- V049 N/N−1 Gate 通过，Evidence Hash：
  `38c296eef79ac8fcfc26dfba2b3c59eba5c8a533d5591fd4b9d466ba4be1798a`；
- PostgreSQL 17 已从空库真实应用 49 个 Flyway Migration 并完成 Hibernate 启动。

本机完整 Integration 在 V049 成功启动后，被既有 fake HTTP proxy 出口请求连续两次
阻断；本地 MinIO 镜像也被配置的 USTC Docker Mirror EOF 阻断。它们均发生在本轮业务
断言之前，最终完整 PostgreSQL/Node/Object Storage/E2E 结果以本次 GitHub CI 为准。

## 仍需完成

1. Purpose-bound 原始截图下载/短期签名 URL、访问审计和管理员权限 Gate；
2. Screenshot 保留期、Legal Hold、WORM Manifest、删除 Receipt 和租户配额；
3. 敏感区域识别、截图模糊/遮罩和站点级隐私策略；
4. Observer 手动截图与截图频率执行器；本轮只闭环 Agent Navigate/Action；
5. 目标 Linux 多 Session 长稳、磁盘满、Object Storage 背压/网络分区和告警矩阵。
