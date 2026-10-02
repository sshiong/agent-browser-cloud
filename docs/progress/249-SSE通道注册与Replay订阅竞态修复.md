# SSE 通道注册与 Replay 订阅竞态修复

> 日期：2026-10-03
> 范围：Session/Resource、Workspace Overview、Enterprise Overview、Notification、Audit。

## 可重复证据

进度 248 保留了 CI `37006769122` 中实时安全租约事件未到达的失败。审查发现
两个独立的事件流竞态，已用受控 Store 回调交错复现，不依赖随机睡眠或重复 CI。

1. 首个订阅先将空通道放入 Map，再读取 PostgreSQL 游标，最后加入 Subscriber。
   读取期间 Publisher 可把空通道移出 Map。随后订阅收到 ready，但后续定时发布
   找不到该通道，连接仍占订阅配额却不再读取实时变化。
2. Publisher 按既有订阅的最小游标查询。查询期间新 Replay 订阅加入，原先遍历
   当前完整 Subscriber 列表发送查询结果，新订阅可能直接被较新事件推进游标，
   跳过尚未查询的历史。例如既有游标 10、新订阅游标 4、当前读取返回事件 11，
   新订阅会漏掉 5—10。

Session 两项回归在旧代码上失败：注册完成后的 Publisher 没有再次查询 Store；
新订阅下一次查询使用 11 而非 4。五类事件流存在同类代码，均补入两项回归。
这证明代码缺口，不足以确定那次 CI 失败是否正好发生了此交错。

## 修改

- 通道选择与待注册计数在同一个 Map `compute` 中原子完成。注册期间的通道
  不可回收；每个成功或失败出口在 finally 释放计数。
- 空通道回收通过同一 Map 的 `computeIfPresent`，只回收仍是该实例、无待注册
  且无 Subscriber 的通道。旧实例的回调不能移除新实例。
- 每次发布先固定活动 Subscriber 集合，用同一集合计算查询游标并发送查询结果。
  查询期间新加入的订阅等待下一次按自身游标读取，不被其他订阅的结果推进。
- 保留原 Tenant/Session/平台事件隔离、配额策略、ready/reset/replayed 协议、
  一秒轮询、十五秒心跳和 PostgreSQL 事件源。未延长 Integration 的五秒事件
  等待，未删除断言或使用进程内数据伪造业务变化。
- API、数据库、OpenAPI、Protobuf、SDK 未变更。

## 验证

- Session 两项旧逻辑失败；五类共十项新回归及原有测试合计 27 项通过。
- Control Plane 全部 618 项测试通过，Spotless 检查与 Boot JAR 构建通过。
- OrbStack 完整 Integration 通过，实时安全租约、资源、Overview、Notification、Audit
  事件断言和后续业务链均保留；N/N−1 Gate、文档七项、双语目录/链接及差异检查通过。

本机日志：`/tmp/agentbrowser-sse-channel-before.log`、
`/tmp/agentbrowser-sse-channel-target.log`、`/tmp/agentbrowser-sse-channel-java.log`、
`/tmp/agentbrowser-sse-channel-integration.log`。第一次格式任务误用了缓存中的
`/workspace` 文件路径而失败；强制重算格式任务、关闭构建/配置缓存后成功。
没有修改格式规则或跳过检查。

前一精确提交 `866ef6bd82c958a3ff988cb05fe93674af8e98d7` 的 CI `37009122207`
和 Desktop `37009122305` 均完整通过。本次修改仍须独立提交并检查 CI。

## 公开完整回放与剩余 Gate

`866ef6b` 对应源码重建的 Node 在 OrbStack 运行原 17 例 Dataset，Session
`ses_caa7edfc88974879` 于错误密码登录后的明确 alert 等待失败；尚未完成 17 例。
最后 API 投影为 COMPLETE/STALE，Document complete，网络 quiet 7046 毫秒。
这些字段是最后一次投影，不能用来证明失败时当前网页或业务提交已完成。
独立 CDP 在清理附近报告 Practice Document POST 约 45 秒后失败，归为 OTHER；
没有响应状态证明，不能据此判定站点、代理或 CDP 根因，也不忽略在途表单。

Harness 与只读观察器均已结束，私有证据目录
`/tmp/agentbrowser-full17-private.1imOZb/`。本轮异常摘要未输出页面 value、URL Query
或标题。该路径的脱敏不等于任意第三方异常或所有进程日志都已脱敏。

完整公开矩阵连续稳定性仍开放。客户 Adapter/Provider/Replay、真实 OTP/企业
IdP/支付、Linux/云/硬件 Codec/跨 Region、供应商取消回执、组织发布证据与权利人
许可证选择也仍开放。本次 SSE 竞态修复不代替这些环境和集成 Gate。
