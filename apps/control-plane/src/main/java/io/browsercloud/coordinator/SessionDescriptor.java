package io.browsercloud.coordinator;

import io.browsercloud.domain.session.SessionContext;

/**
 * Session 查询投影。
 *
 * <p>运行时协调只依赖 {@link SessionContext}；面向 API 的查询额外携带 Session 主记录中的展示字段， 避免把任意 metadata 暴露给客户端。
 */
public record SessionDescriptor(
    SessionContext context, String region, String displayName, String groupId) {}
