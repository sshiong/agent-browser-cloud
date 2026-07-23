# ADR-005：控制面采用 Java，Browser Node 采用 Rust

- 状态：Accepted
- 日期：2026-07-23

## 决策

Control Plane 使用 Java 21 与 Spring Boot；Browser Node 使用 Rust、Tokio 与 Tonic。跨语言边界只通过版本化 Protobuf/OpenAPI 契约。

## 原因

Java 生态适合事务、JPA、控制面治理与运营集成；Rust 适合进程监督、输入账本和节点级资源控制。边界必须保持显式，不能共享数据库实体或让 Node 直接写控制面数据库。

## 后果

CI 必须同时验证 Java、Rust 与契约兼容性。Node 的故障不能通过直接数据库写入“修复”，只能生成事件并由 Session Coordinator 提交。
