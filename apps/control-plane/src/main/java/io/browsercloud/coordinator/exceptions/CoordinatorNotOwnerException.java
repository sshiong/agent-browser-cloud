package io.browsercloud.coordinator.exceptions;

/** 当前 Control Plane 实例不持有 Session Coordinator Lease。 */
public class CoordinatorNotOwnerException extends RuntimeException {

  public CoordinatorNotOwnerException(String sessionId) {
    super("Coordinator lease is held by another instance for session " + sessionId);
  }
}
