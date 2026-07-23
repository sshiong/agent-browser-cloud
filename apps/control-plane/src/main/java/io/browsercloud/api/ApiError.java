package io.browsercloud.api;

import java.time.Instant;
import java.util.Map;

/** 稳定、可机器处理的 API 错误信封。 */
public record ApiError(
    String code,
    String message,
    Map<String, Object> details,
    String requestId,
    Instant timestamp) {}
