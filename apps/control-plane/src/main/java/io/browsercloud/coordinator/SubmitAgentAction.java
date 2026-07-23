package io.browsercloud.coordinator;

/**
 * 提交 Agent Action 命令。
 *
 * @param sessionId Session ID
 * @param actionId Action ID
 * @param actionType Action 类型
 */
public record SubmitAgentAction(String sessionId, String actionId, String actionType)
    implements SessionCommand {}
