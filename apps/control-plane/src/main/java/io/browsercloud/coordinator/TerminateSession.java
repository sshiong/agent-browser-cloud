package io.browsercloud.coordinator;

/**
 * 终止 Session 命令。
 *
 * @param sessionId Session ID
 * @param reason 终止原因
 */
public record TerminateSession(String sessionId, String reason) implements SessionCommand {}
