package io.browsercloud.coordinator;

/**
 * Agent Executor 在创建或恢复任务前请求 Coordinator 校验 Ownership 与旧世代 Operation。
 *
 * @param sessionId Session ID
 * @param taskId Agent Task ID，仅用于审计关联
 */
public record ReconcileAgentExecution(String sessionId, String taskId) implements SessionCommand {}
