package io.browsercloud.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.CoordinatorOwnershipService;
import io.browsercloud.coordinator.SessionDescriptor;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import io.browsercloud.coordinator.exceptions.StaleContextEpochException;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.SessionContextEntity;
import io.browsercloud.persistence.SessionEntity;
import io.browsercloud.persistence.SessionJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Session Repository JPA 实现。 */
@Repository
public class JpaSessionRepository implements SessionRepository {

  private final SessionJpaRepository sessionJpa;
  private final SessionContextJpaRepository contextJpa;
  private final ObjectMapper objectMapper;
  private final CoordinatorOwnershipService ownershipService;

  public JpaSessionRepository(
      SessionJpaRepository sessionJpa,
      SessionContextJpaRepository contextJpa,
      ObjectMapper objectMapper,
      CoordinatorOwnershipService ownershipService) {
    this.sessionJpa = sessionJpa;
    this.contextJpa = contextJpa;
    this.objectMapper = objectMapper;
    this.ownershipService = ownershipService;
  }

  @Override
  public SessionContext require(String sessionId) {
    var entity =
        sessionJpa.findById(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));

    // 获取最新的 Context
    var contextEntity = contextJpa.findTopBySessionIdOrderByContextEpochDesc(sessionId);

    return toDomain(entity, contextEntity);
  }

  @Override
  public SessionDescriptor describe(String sessionId) {
    var entity =
        sessionJpa.findById(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
    return toDescriptor(entity, contextJpa.findTopBySessionIdOrderByContextEpochDesc(sessionId));
  }

  @Override
  @Transactional
  public SessionContext requireForUpdate(String sessionId) {
    var entity =
        sessionJpa
            .findWithLockById(sessionId)
            .orElseThrow(() -> new SessionNotFoundException(sessionId));
    return toDomain(entity, contextJpa.findTopBySessionIdOrderByContextEpochDesc(sessionId));
  }

  @Override
  @Transactional
  public void insert(SessionContext context, String region, Map<String, String> metadata) {
    var entity =
        new SessionEntity(
            context.sessionId(),
            context.tenantId(),
            context.profileId(),
            region,
            context.resourceClass().name(),
            context.state().name(),
            context.policyHash(),
            serializeMetadata(metadata),
            context.updatedAt());
    sessionJpa.save(entity);

    // 插入初始 Context
    var contextEntity = new SessionContextEntity();
    contextEntity.setSessionId(context.sessionId());
    contextEntity.setContextEpoch(context.contextEpoch());
    contextEntity.setCoordinatorTerm(context.coordinatorTerm());
    contextEntity.setNodeId(context.nodeId());
    contextEntity.setRuntimeBuildId(context.runtimeBuildId());
    contextEntity.setIsolationProfileId(context.isolationProfileId());
    contextEntity.setProxyBindingId(context.proxyBindingId());
    contextEntity.setNetworkRevision(context.networkRevision());
    contextEntity.setBrowserGeneration(context.browserGeneration());
    contextEntity.setResourceClass(context.resourceClass().name());
    contextEntity.setPolicyHash(context.policyHash());
    contextEntity.setCommittedAt(context.updatedAt());
    contextJpa.save(contextEntity);
  }

  private String serializeMetadata(Map<String, String> metadata) {
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Session metadata is not serializable", exception);
    }
  }

  @Override
  @Transactional
  public void updateWithExpectedEpoch(SessionContext context, long expectedContextEpoch) {
    // 对 Session 主行加锁，并校验最新 Context Epoch，避免并发写覆盖。
    var entity =
        sessionJpa
            .findWithLockById(context.sessionId())
            .orElseThrow(() -> new SessionNotFoundException(context.sessionId()));
    var latest = contextJpa.findTopBySessionIdOrderByContextEpochDesc(context.sessionId());
    long actualEpoch = latest.map(SessionContextEntity::getContextEpoch).orElse(-1L);
    if (actualEpoch != expectedContextEpoch) {
      throw new StaleContextEpochException(context.sessionId(), expectedContextEpoch, actualEpoch);
    }
    if (context.contextEpoch() < expectedContextEpoch
        || context.contextEpoch() > expectedContextEpoch + 1) {
      throw new StaleContextEpochException(
          context.sessionId(), expectedContextEpoch + 1, context.contextEpoch());
    }

    entity.setState(context.state().name());
    entity.setResourceClass(context.resourceClass().name());
    entity.setUpdatedAt(Instant.now());
    if (context.state() == SessionState.TERMINATED) {
      entity.setTerminatedAt(Instant.now());
    }
    sessionJpa.save(entity);

    // 只有核心环境变化才递增 Epoch 并追加不可变 Context Commit。
    if (context.contextEpoch() == expectedContextEpoch) {
      return;
    }

    var contextEntity = new SessionContextEntity();
    contextEntity.setSessionId(context.sessionId());
    contextEntity.setContextEpoch(context.contextEpoch());
    contextEntity.setCoordinatorTerm(context.coordinatorTerm());
    contextEntity.setNodeId(context.nodeId());
    contextEntity.setRuntimeBuildId(context.runtimeBuildId());
    contextEntity.setIsolationProfileId(context.isolationProfileId());
    contextEntity.setProxyBindingId(context.proxyBindingId());
    contextEntity.setNetworkRevision(context.networkRevision());
    contextEntity.setBrowserGeneration(context.browserGeneration());
    contextEntity.setResourceClass(context.resourceClass().name());
    contextEntity.setPolicyHash(context.policyHash());
    contextEntity.setCommittedAt(Instant.now());
    contextJpa.save(contextEntity);
  }

  @Override
  public List<SessionDescriptor> listByTenant(
      String tenantId, SessionState state, int limit, int offset) {
    int safeLimit = Math.max(1, Math.min(limit, 100));
    int safeOffset = Math.max(0, offset);
    var pageable =
        new OffsetPageRequest(safeOffset, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt"));
    var page =
        state == null
            ? sessionJpa.findAllByTenantId(tenantId, pageable)
            : sessionJpa.findAllByTenantIdAndState(tenantId, state.name(), pageable);
    return page.getContent().stream()
        .map(
            entity ->
                toDescriptor(
                    entity, contextJpa.findTopBySessionIdOrderByContextEpochDesc(entity.getId())))
        .toList();
  }

  @Override
  public long countByTenant(String tenantId, SessionState state) {
    return state == null
        ? sessionJpa.countByTenantId(tenantId)
        : sessionJpa.countByTenantIdAndState(tenantId, state.name());
  }

  private SessionContext toDomain(SessionEntity entity, Optional<SessionContextEntity> contextOpt) {
    long coordinatorTerm = 0;
    long contextEpoch = 0;
    long browserGeneration = 0;
    long networkRevision = 0;
    String nodeId = null;
    String runtimeBuildId = null;
    String isolationProfileId = null;
    String proxyBindingId = null;

    if (contextOpt.isPresent()) {
      var ctx = contextOpt.get();
      coordinatorTerm = ctx.getCoordinatorTerm();
      contextEpoch = ctx.getContextEpoch();
      browserGeneration = ctx.getBrowserGeneration();
      networkRevision = ctx.getNetworkRevision();
      nodeId = ctx.getNodeId();
      runtimeBuildId = ctx.getRuntimeBuildId();
      isolationProfileId = ctx.getIsolationProfileId();
      proxyBindingId = ctx.getProxyBindingId();
    }

    coordinatorTerm = Math.max(coordinatorTerm, ownershipService.getCurrentTerm(entity.getId()));

    return new SessionContext(
        entity.getId(),
        entity.getTenantId(),
        entity.getProfileId(),
        nodeId,
        runtimeBuildId,
        isolationProfileId,
        proxyBindingId,
        coordinatorTerm,
        contextEpoch,
        browserGeneration,
        networkRevision,
        ResourceClass.valueOf(entity.getResourceClass()),
        SessionState.valueOf(entity.getState()),
        entity.getPolicyHash(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private SessionDescriptor toDescriptor(
      SessionEntity entity, Optional<SessionContextEntity> contextOpt) {
    var context = toDomain(entity, contextOpt);
    return new SessionDescriptor(
        context, entity.getRegion(), readDisplayName(entity.getMetadata(), entity.getId()));
  }

  private String readDisplayName(String metadata, String fallback) {
    if (metadata == null || metadata.isBlank()) {
      return fallback;
    }
    try {
      var root = objectMapper.readTree(metadata);
      var value = root == null ? null : root.get("displayName");
      if (value == null || !value.isTextual() || value.textValue().isBlank()) {
        return fallback;
      }
      var displayName = value.textValue().strip();
      return displayName.length() <= 128 ? displayName : displayName.substring(0, 128);
    } catch (JsonProcessingException exception) {
      return fallback;
    }
  }
}
