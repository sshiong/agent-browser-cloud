package io.browsercloud.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.CoordinatorOwnershipService;
import io.browsercloud.coordinator.SessionDescriptor;
import io.browsercloud.coordinator.SessionListFilter;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import io.browsercloud.coordinator.exceptions.StaleContextEpochException;
import io.browsercloud.domain.agent.AgentPolicy;
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
import java.util.function.Function;
import java.util.stream.Collectors;
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
  private final SessionFilteredQueryRepository filteredQueries;

  public JpaSessionRepository(
      SessionJpaRepository sessionJpa,
      SessionContextJpaRepository contextJpa,
      ObjectMapper objectMapper,
      CoordinatorOwnershipService ownershipService,
      SessionFilteredQueryRepository filteredQueries) {
    this.sessionJpa = sessionJpa;
    this.contextJpa = contextJpa;
    this.objectMapper = objectMapper;
    this.ownershipService = ownershipService;
    this.filteredQueries = filteredQueries;
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
  public void lockForUpdate(String sessionId) {
    sessionJpa
        .findWithLockById(sessionId)
        .orElseThrow(() -> new SessionNotFoundException(sessionId));
  }

  @Override
  @Transactional
  public void insert(
      SessionContext context,
      String region,
      Map<String, String> metadata,
      String groupId,
      boolean humanTakeoverEnabled,
      AgentPolicy agentPolicy,
      List<String> extensionIds) {
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
            humanTakeoverEnabled,
            agentPolicy,
            serialize(extensionIds),
            context.updatedAt());
    entity.setGroupId(groupId);
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
    return serialize(metadata);
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Session data is not serializable", exception);
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
      String tenantId,
      SessionState state,
      String query,
      SessionListFilter filter,
      int limit,
      int offset) {
    int safeLimit = Math.max(1, Math.min(limit, 100));
    int safeOffset = Math.max(0, offset);
    var pageable =
        new OffsetPageRequest(safeOffset, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt"));
    var searchPageable = new OffsetPageRequest(safeOffset, safeLimit, Sort.unsorted());
    var normalizedQuery = query == null ? "" : query.trim();
    var repositoryQuery = escapeLikeLiteral(normalizedQuery);
    List<SessionEntity> entities;
    if (filter.hasWorkspaceDimensions()) {
      entities =
          filteredQueries.list(tenantId, state, normalizedQuery, filter, safeLimit, safeOffset);
    } else {
      var page =
          normalizedQuery.isEmpty()
              ? state == null
                  ? sessionJpa.findAllByTenantId(tenantId, pageable)
                  : sessionJpa.findAllByTenantIdAndState(tenantId, state.name(), pageable)
              : state == null
                  ? sessionJpa.searchAllByTenantId(tenantId, repositoryQuery, searchPageable)
                  : sessionJpa.searchAllByTenantIdAndState(
                      tenantId, state.name(), repositoryQuery, searchPageable);
      entities = page.getContent();
    }
    if (entities.isEmpty()) {
      return List.of();
    }
    var sessionIds = entities.stream().map(SessionEntity::getId).toList();
    var contextsBySession =
        contextJpa.findLatestBySessionIds(sessionIds).stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    SessionContextEntity::getSessionId, Function.identity()));
    var ownershipTerms = ownershipService.getCurrentTerms(sessionIds);
    return entities.stream()
        .map(
            entity ->
                toDescriptor(
                    entity,
                    Optional.ofNullable(contextsBySession.get(entity.getId())),
                    ownershipTerms.getOrDefault(entity.getId(), 0L)))
        .toList();
  }

  @Override
  public long countByTenant(
      String tenantId, SessionState state, String query, SessionListFilter filter) {
    var normalizedQuery = query == null ? "" : query.trim();
    if (filter.hasWorkspaceDimensions()) {
      return filteredQueries.count(tenantId, state, normalizedQuery, filter);
    }
    var repositoryQuery = escapeLikeLiteral(normalizedQuery);
    if (!normalizedQuery.isEmpty()) {
      var pageable = new OffsetPageRequest(0, 1, Sort.unsorted());
      return state == null
          ? sessionJpa.searchAllByTenantId(tenantId, repositoryQuery, pageable).getTotalElements()
          : sessionJpa
              .searchAllByTenantIdAndState(tenantId, state.name(), repositoryQuery, pageable)
              .getTotalElements();
    }
    return state == null
        ? sessionJpa.countByTenantId(tenantId)
        : sessionJpa.countByTenantIdAndState(tenantId, state.name());
  }

  private static String escapeLikeLiteral(String query) {
    return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private SessionContext toDomain(SessionEntity entity, Optional<SessionContextEntity> contextOpt) {
    return toDomain(entity, contextOpt, ownershipService.getCurrentTerm(entity.getId()));
  }

  private SessionContext toDomain(
      SessionEntity entity,
      Optional<SessionContextEntity> contextOpt,
      long authoritativeOwnershipTerm) {
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

    coordinatorTerm = Math.max(coordinatorTerm, authoritativeOwnershipTerm);

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
    return toDescriptor(entity, contextOpt, ownershipService.getCurrentTerm(entity.getId()));
  }

  private SessionDescriptor toDescriptor(
      SessionEntity entity,
      Optional<SessionContextEntity> contextOpt,
      long authoritativeOwnershipTerm) {
    var context = toDomain(entity, contextOpt, authoritativeOwnershipTerm);
    return new SessionDescriptor(
        context,
        entity.getRegion(),
        readDisplayName(entity.getMetadata(), entity.getId()),
        entity.getGroupId(),
        entity.isHumanTakeoverEnabled(),
        entity.getAgentPolicy(),
        readExtensionIds(entity.getExtensionIds()));
  }

  private List<String> readExtensionIds(String extensionIds) {
    if (extensionIds == null || extensionIds.isBlank()) {
      return List.of();
    }
    try {
      return List.copyOf(
          objectMapper.readValue(extensionIds, new TypeReference<List<String>>() {}));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Persisted Session Extension binding is invalid", exception);
    }
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
