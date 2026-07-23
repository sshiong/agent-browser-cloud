# ADR-004：Profile 与 Cache 分层

- 状态：Accepted
- 日期：2026-07-23

## 决策

Profile Core 是需要加密、版本化和可恢复的权威用户状态；Cache、视频帧、临时下载与可重建索引属于可丢弃派生数据。两者使用不同目录、保留策略、配额与清理流程。

## 原因

把 Cache 纳入 Profile Checkpoint 会放大上传、恢复和隐私暴露面，并使损坏缓存影响登录态恢复。Checkpoint Manifest 只列出恢复必需文件，并由 Commit Marker 证明完整提交。

## 后果

Runtime 启动器必须显式分配 Core、Ephemeral 和 Cache 路径。垃圾回收可以删除 Cache，但不得删除已提交且仍在保留期内的 Profile Core。
