package io.browsercloud.coordinator;

/**
 * Operation 超时命令。
 *
 * @param sessionId Session ID
 * @param operationId Operation ID
 */
public record OperationTimedOut(String sessionId, String operationId) implements SessionCommand {}
