package io.browsercloud.coordinator;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Operation 超时事件。 */
public final class OperationTimedOutEvent extends DomainEvent {

  private final String sessionId;
  private final String operationId;

  public OperationTimedOutEvent(String sessionId, String operationId) {
    super("operation.timed_out", "operation", operationId);
    this.sessionId = sessionId;
    this.operationId = operationId;
  }

  @JsonProperty
  public String sessionId() {
    return sessionId;
  }

  @JsonProperty
  public String operationId() {
    return operationId;
  }
}
