package io.browsercloud.coordinator;

import java.util.Optional;

/** Browser Current State 的控制面读写端口。 */
public interface BrowserStateRepository {

  void save(String tenantId, long contextEpoch, NodeEvent.StateUpdated state);

  Optional<Snapshot> find(String sessionId);

  record Snapshot(String tenantId, long contextEpoch, NodeEvent.StateUpdated state) {}
}
