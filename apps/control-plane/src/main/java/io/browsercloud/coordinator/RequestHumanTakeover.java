package io.browsercloud.coordinator;

/**
 * 请求人工接管命令。
 *
 * @param sessionId Session ID
 * @param userId 用户 ID
 */
public record RequestHumanTakeover(String sessionId, String userId) implements SessionCommand {}
