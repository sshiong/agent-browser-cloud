package io.browsercloud.coordinator;

import java.util.Optional;

/** Browser Current State 的控制面读写端口。 */
public interface BrowserStateRepository {

  void save(String tenantId, long contextEpoch, NodeEvent.StateUpdated state);

  boolean applyDiff(String tenantId, long contextEpoch, NodeEvent.StateDiff diff);

  void invalidate(
      String tenantId, long contextEpoch, String sessionId, long stateVersion, String reason);

  void markResyncing(String tenantId, long contextEpoch, String sessionId);

  Optional<Snapshot> find(String sessionId);

  record Snapshot(String tenantId, long contextEpoch, NodeEvent.StateUpdated state) {}
}
