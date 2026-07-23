package io.browsercloud.coordinator.exceptions;

/** Context Epoch 不匹配异常。 */
public class StaleContextEpochException extends RuntimeException {

  private final String sessionId;
  private final long expected;
  private final long actual;

  public StaleContextEpochException(String sessionId, long expected, long actual) {
    super(
        "Stale context epoch for session: "
            + sessionId
            + ", expected: "
            + expected
            + ", actual: "
            + actual);
    this.sessionId = sessionId;
    this.expected = expected;
    this.actual = actual;
  }

  public String sessionId() {
    return sessionId;
  }

  public long expected() {
    return expected;
  }

  public long actual() {
    return actual;
  }
}
