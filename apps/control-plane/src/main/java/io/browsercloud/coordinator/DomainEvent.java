package io.browsercloud.coordinator;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 领域事件基类。 */
public abstract class DomainEvent {

  private final String eventType;
  private final String aggregateType;
  private final String aggregateId;

  protected DomainEvent(String eventType, String aggregateType, String aggregateId) {
    this.eventType = eventType;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
  }

  @JsonProperty
  public String eventType() {
    return eventType;
  }

  @JsonProperty
  public String aggregateType() {
    return aggregateType;
  }

  @JsonProperty
  public String aggregateId() {
    return aggregateId;
  }
}
