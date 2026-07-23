package io.browsercloud.persistence;

import java.io.Serializable;
import java.util.Objects;

/** Session Context 复合主键。 */
public class SessionContextId implements Serializable {

  private String sessionId;
  private long contextEpoch;

  public SessionContextId() {}

  public SessionContextId(String sessionId, long contextEpoch) {
    this.sessionId = sessionId;
    this.contextEpoch = contextEpoch;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SessionContextId that = (SessionContextId) o;
    return contextEpoch == that.contextEpoch && Objects.equals(sessionId, that.sessionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sessionId, contextEpoch);
  }
}
