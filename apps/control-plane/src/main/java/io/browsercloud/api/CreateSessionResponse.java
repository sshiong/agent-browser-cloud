package io.browsercloud.api;

/**
 * 创建 Session 响应。
 *
 * @param sessionId Session ID
 * @param operationId 资源策略创建 Operation ID
 * @param state 创建状态
 * @param resourcePolicy 已解析的自动资源策略
 * @param context Session 上下文
 */
public record CreateSessionResponse(
    String sessionId,
    String operationId,
    String state,
    SessionResourceModels.PolicyView resourcePolicy,
    SessionContextView context) {}
