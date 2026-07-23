package io.browsercloud.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.persistence.BrowserStateEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaBrowserStateRepository implements BrowserStateRepository {

  private final BrowserStateJpaRepository repository;
  private final ObjectMapper objectMapper;

  public JpaBrowserStateRepository(
      BrowserStateJpaRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Override
  public void save(String tenantId, long contextEpoch, NodeEvent.StateUpdated state) {
    var existing = repository.findById(state.sessionId()).orElseGet(BrowserStateEntity::new);
    if (existing.getSessionId() != null
        && (existing.getContextEpoch() > contextEpoch
            || (existing.getContextEpoch() == contextEpoch
                && existing.getStateVersion() >= state.stateVersion()))) {
      return;
    }
    existing.setSessionId(state.sessionId());
    existing.setTenantId(tenantId);
    existing.setContextEpoch(contextEpoch);
    existing.setStateVersion(state.stateVersion());
    existing.setStateJson(write(state));
    existing.setUpdatedAt(Instant.now());
    repository.save(existing);
  }

  @Override
  public Optional<Snapshot> find(String sessionId) {
    return repository
        .findById(sessionId)
        .map(
            entity ->
                new Snapshot(
                    entity.getTenantId(), entity.getContextEpoch(), read(entity.getStateJson())));
  }

  private String write(NodeEvent.StateUpdated state) {
    try {
      return objectMapper.writeValueAsString(state);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize Browser State", exception);
    }
  }

  private NodeEvent.StateUpdated read(String json) {
    try {
      return objectMapper.readValue(json, NodeEvent.StateUpdated.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to deserialize Browser State", exception);
    }
  }
}
