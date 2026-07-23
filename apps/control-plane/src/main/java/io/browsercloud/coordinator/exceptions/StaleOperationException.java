package io.browsercloud.coordinator.exceptions;

/** Operation 状态不匹配异常。 */
public class StaleOperationException extends RuntimeException {

  private final String operationId;
  private final String expectedState;
  private final String actualState;

  public StaleOperationException(String operationId, String expectedState, String actualState) {
    super(
        "Stale operation: "
            + operationId
            + ", expected state: "
            + expectedState
            + ", actual state: "
            + actualState);
    this.operationId = operationId;
    this.expectedState = expectedState;
    this.actualState = actualState;
  }

  public String operationId() {
    return operationId;
  }

  public String expectedState() {
    return expectedState;
  }

  public String actualState() {
    return actualState;
  }
}
