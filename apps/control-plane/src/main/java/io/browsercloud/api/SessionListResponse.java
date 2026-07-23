package io.browsercloud.api;

import java.util.List;

/**
 * Session 列表响应。
 *
 * @param items Session 列表
 * @param total 总数
 * @param limit 每页数量
 * @param offset 偏移量
 */
public record SessionListResponse(List<SessionView> items, int total, int limit, int offset) {}
