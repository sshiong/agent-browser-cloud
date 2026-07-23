package io.browsercloud.coordinator.exceptions;

/** 已有活跃 Operation 异常。 */
public class ActiveOperationExistsException extends RuntimeException {

  private final String sessionId;
  private final String existingOperationId;

  public ActiveOperationExistsException(String sessionId, String existingOperationId) {
    super(
        "Active operation already exists for session: "
            + sessionId
            + ", operation: "
            + existingOperationId);
    this.sessionId = sessionId;
    this.existingOperationId = existingOperationId;
  }

  public String sessionId() {
    return sessionId;
  }

  public String existingOperationId() {
    return existingOperationId;
  }
}
