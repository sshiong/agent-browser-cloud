package io.browsercloud.api;

import io.browsercloud.domain.operation.OperationState;

/**
 * 操作响应。
 *
 * @param operationId 操作 ID
 * @param state 操作状态
 */
public record OperationResponse(String operationId, OperationState state) {}
