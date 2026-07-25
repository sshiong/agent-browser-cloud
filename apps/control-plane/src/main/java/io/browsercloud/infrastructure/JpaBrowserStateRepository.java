package io.browsercloud.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.persistence.BrowserStateEntity;
import java.time.Instant;
import java.util.LinkedHashMap;
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
  public boolean applyDiff(String tenantId, long contextEpoch, NodeEvent.StateDiff diff) {
    var entity = repository.findById(diff.sessionId()).orElse(null);
    if (entity == null
        || !entity.getTenantId().equals(tenantId)
        || entity.getContextEpoch() != contextEpoch
        || entity.getStateVersion() != diff.baseStateVersion()) {
      return false;
    }
    var previous = read(entity.getStateJson());
    if (previous.stateQuality().equals("INVALID") || previous.stateQuality().equals("RESYNCING")) {
      return false;
    }
    var targets = new LinkedHashMap<String, NodeEvent.InteractiveTarget>();
    previous.targets().forEach(target -> targets.put(target.targetRef(), target));
    diff.removedTargetRefs().forEach(targets::remove);
    diff.upsertedTargets().forEach(target -> targets.put(target.targetRef(), target));
    if (targets.size() > 500) {
      return false;
    }
    var updated =
        new NodeEvent.StateUpdated(
            diff.sessionId(),
            diff.stateVersion(),
            diff.targetRevision(),
            diff.url(),
            diff.title(),
            diff.stateHash(),
            diff.stateQuality(),
            targets.values().stream().toList());
    entity.setStateVersion(diff.stateVersion());
    entity.setStateJson(write(updated));
    entity.setUpdatedAt(Instant.now());
    repository.save(entity);
    return true;
  }

  @Override
  public void invalidate(
      String tenantId, long contextEpoch, String sessionId, long stateVersion, String reason) {
    repository
        .findById(sessionId)
        .filter(entity -> entity.getTenantId().equals(tenantId))
        .filter(entity -> entity.getContextEpoch() == contextEpoch)
        .ifPresent(
            entity -> {
              var previous = read(entity.getStateJson());
              var invalid =
                  new NodeEvent.StateUpdated(
                      sessionId,
                      Math.max(stateVersion, previous.stateVersion()),
                      previous.targetRevision(),
                      previous.url(),
                      previous.title(),
                      previous.stateHash(),
                      "INVALID",
                      previous.targets());
              entity.setStateVersion(invalid.stateVersion());
              entity.setStateJson(write(invalid));
              entity.setUpdatedAt(Instant.now());
              repository.save(entity);
            });
  }

  @Override
  public void markResyncing(String tenantId, long contextEpoch, String sessionId) {
    repository
        .findById(sessionId)
        .filter(entity -> entity.getTenantId().equals(tenantId))
        .filter(entity -> entity.getContextEpoch() == contextEpoch)
        .ifPresent(
            entity -> {
              var previous = read(entity.getStateJson());
              var resyncing =
                  new NodeEvent.StateUpdated(
                      sessionId,
                      previous.stateVersion(),
                      previous.targetRevision(),
                      previous.url(),
                      previous.title(),
                      previous.stateHash(),
                      "RESYNCING",
                      previous.targets());
              entity.setStateJson(write(resyncing));
              entity.setUpdatedAt(Instant.now());
              repository.save(entity);
            });
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
