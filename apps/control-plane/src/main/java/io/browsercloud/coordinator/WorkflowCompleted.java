package io.browsercloud.coordinator;

/**
 * Workflow 完成命令。
 *
 * @param sessionId Session ID
 * @param workflowId Workflow ID
 * @param success 是否成功
 */
public record WorkflowCompleted(String sessionId, String workflowId, boolean success)
    implements SessionCommand {}
