package io.browsercloud.coordinator.exceptions;

/** Browser Node 上报了当前 Coordinator 世代之前产生的事件。 */
public class StaleCoordinatorTermException extends RuntimeException {

  public StaleCoordinatorTermException(String sessionId, long requestedTerm, long currentTerm) {
    super(
        "Stale coordinator term for session "
            + sessionId
            + ": requested="
            + requestedTerm
            + ", current="
            + currentTerm);
  }
}
