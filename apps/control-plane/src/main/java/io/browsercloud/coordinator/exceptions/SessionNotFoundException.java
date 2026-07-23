package io.browsercloud.coordinator.exceptions;

/** Session 不存在异常。 */
public class SessionNotFoundException extends RuntimeException {

  private final String sessionId;

  public SessionNotFoundException(String sessionId) {
    super("Session not found: " + sessionId);
    this.sessionId = sessionId;
  }

  public String sessionId() {
    return sessionId;
  }
}
