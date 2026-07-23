package io.browsercloud.coordinator;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.browsercloud.domain.session.SessionState;

/** Session 状态变更事件。 */
public final class SessionStateChanged extends DomainEvent {

  private final SessionState newState;

  public SessionStateChanged(String sessionId, SessionState newState) {
    super("session.state.changed", "session", sessionId);
    this.newState = newState;
  }

  @JsonProperty
  public SessionState newState() {
    return newState;
  }
}
