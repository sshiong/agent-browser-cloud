package io.browsercloud.coordinator.exceptions;

import io.browsercloud.domain.session.SessionState;

/** Session 当前状态不允许执行请求的命令。 */
public class InvalidSessionStateException extends RuntimeException {

  private final String sessionId;
  private final SessionState state;

  public InvalidSessionStateException(String sessionId, SessionState state, String action) {
    super("Session " + sessionId + " in state " + state + " cannot " + action);
    this.sessionId = sessionId;
    this.state = state;
  }

  public String sessionId() {
    return sessionId;
  }

  public SessionState state() {
    return state;
  }
}
