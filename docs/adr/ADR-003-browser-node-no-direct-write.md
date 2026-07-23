# ADR-003: Browser Node 不直接写控制面

## 状态

Accepted

## 背景

Browser Node 是高权限组件，需要访问操作系统资源（uinput、Display、网络命名空间等）。如果 Browser Node 可以直接写控制面数据库，会增加攻击面。

## 决策

Browser Node 不直接写控制面业务表：
- 所有状态变更通过命令/事件模式
- Browser Node 发送事件到 Control Plane
- Control Plane 负责验证和持久化

## 后果

### 优点
- 攻击面隔离
- Control Plane 保持数据一致性
- Browser Node 崩溃不会破坏数据
- 审计追踪完整

### 缺点
- 增加网络往返延迟
- 需要实现可靠的命令/事件通道

## 实现

- 使用 Protobuf 定义命令/事件格式
- 使用 gRPC 或 HTTP 通信
- Browser Node 只能通过 Node Command Gateway 发送命令
