package io.browsercloud.api;

/**
 * 创建 Session 响应。
 *
 * @param sessionId Session ID
 * @param context Session 上下文
 */
public record CreateSessionResponse(String sessionId, SessionContextView context) {}
