package io.browsercloud.coordinator;

/**
 * Node 事件接收命令。
 *
 * @param sessionId Session ID
 * @param event Node 事件
 */
public record NodeEventReceived(String sessionId, NodeEvent event) implements SessionCommand {}
