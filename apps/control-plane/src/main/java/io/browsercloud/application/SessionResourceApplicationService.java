package io.browsercloud.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.BrowserPlacementView;
import io.browsercloud.api.ResourcePolicyRequest;
import io.browsercloud.api.SessionResourceModels.*;
import io.browsercloud.coordinator.OperationFactory;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.resource.*;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-authoritative Session resource policy, real telemetry and event timeline. */
@Service
public class SessionResourceApplicationService {
  private final SessionResourcePolicyJpaRepository policies;
  private final SessionResourceSampleJpaRepository samples;
  private final SessionResourceEventJpaRepository events;
  private final BrowserPlacementJpaRepository placements;
  private final AgentTaskJpaRepository tasks;
  private final SessionRepository sessions;
  private final OperationRepository operations;
  private final IdempotencyService idempotency;
  private final ObjectMapper mapper;

  public SessionResourceApplicationService(
      SessionResourcePolicyJpaRepository policies,
      SessionResourceSampleJpaRepository samples,
      SessionResourceEventJpaRepository events,
      BrowserPlacementJpaRepository placements,
      AgentTaskJpaRepository tasks,
      SessionRepository sessions,
      OperationRepository operations,
      IdempotencyService idempotency,
      ObjectMapper mapper) {
    this.policies = policies;
    this.samples = samples;
    this.events = events;
    this.placements = placements;
    this.tasks = tasks;
    this.sessions = sessions;
    this.operations = operations;
    this.idempotency = idempotency;
    this.mapper = mapper;
  }

  @Transactional
  public ResourcePolicyOperationResponse initialize(
      SessionContext session, ResourcePolicyRequest request, String actorId, String requestId) {
    var now = Instant.now();
    var policy =
        policies.save(
            SessionResourcePolicyEntity.create(
                session.sessionId(), session.tenantId(), request, now));
    var operationId = newId("op_");
    operations.insert(
        OperationFactory.committedResourceAdjustment(
            session, actorId, operations.nextOperationEpoch(session.sessionId()), operationId));
    appendEvent(
        session.sessionId(),
        session.tenantId(),
        "POLICY_CREATED",
        "AUTO_POLICY_ACCEPTED",
        null,
        policyMap(policy),
        "USER_POLICY",
        operationId,
        requestId,
        "COMMITTED",
        now);
    return new ResourcePolicyOperationResponse(
        operationId, OperationState.COMMITTED.name(), toPolicy(policy));
  }

  @Transactional
  public ResourcePolicyOperationResponse update(
      String sessionId,
      String tenantId,
      ResourcePolicyRequest request,
      String idempotencyKey,
      String actorId,
      boolean platformAdmin) {
    var session = requireTenant(sessionId, tenantId);
    if (request != null
        && request.onMaximumReached() == MaximumReachedPolicy.TERMINATE_STRICT
        && !platformAdmin) {
      throw new ResourcePolicyPermissionException();
    }
    var candidateOperation = newId("op_");
    var operationId =
        idempotency.claimResourcePolicy(
            tenantId, sessionId, idempotencyKey, request, candidateOperation);
    var policy = requirePolicy(sessionId, tenantId);
    if (!operationId.equals(candidateOperation)) {
      return new ResourcePolicyOperationResponse(
          operationId, OperationState.COMMITTED.name(), toPolicy(policy));
    }
    var oldPolicy = policyMap(policy);
    policy.apply(request, Instant.now());
    policies.save(policy);
    operations.insert(
        OperationFactory.committedResourceAdjustment(
            session, actorId, operations.nextOperationEpoch(sessionId), operationId));
    appendEvent(
        sessionId,
        tenantId,
        "POLICY_UPDATED",
        "RESOURCE_POLICY_CHANGED",
        oldPolicy,
        policyMap(policy),
        "USER_POLICY",
        operationId,
        idempotencyKey,
        "COMMITTED",
        Instant.now());
    return new ResourcePolicyOperationResponse(
        operationId, OperationState.COMMITTED.name(), toPolicy(policy));
  }

  @Transactional
  public void placementResolved(BrowserPlacementView placement) {
    policies
        .findBySessionIdAndTenantId(placement.sessionId(), placement.tenantId())
        .ifPresent(
            policy -> {
              var old = policyMap(policy);
              var template = templateFor(placement.effectiveResourceClass());
              policy.resolveTemplate(template, Instant.now());
              policies.save(policy);
              appendEvent(
                  placement.sessionId(),
                  placement.tenantId(),
                  "ALLOCATION_RESOLVED",
                  String.join(",", placement.reasonCodes()),
                  old,
                  allocationMap(placement),
                  "PLACEMENT_ENGINE",
                  null,
                  null,
                  "COMMITTED",
                  Instant.now());
            });
  }

  @Transactional(readOnly = true)
  public SessionResourceView get(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    var policy = requirePolicy(sessionId, tenantId);
    var placement = placements.findById(sessionId).orElse(null);
    var recent = samples.findBySessionIdOrderByObservedAtDesc(sessionId, PageRequest.of(0, 60));
    var latest = recent.isEmpty() ? null : recent.getFirst();
    var limit = placement == null ? null : placement.getMemoryLimitMib();
    var freshness =
        latest == null
            ? "AWAITING_TELEMETRY"
            : Duration.between(latest.getObservedAt(), Instant.now()).toSeconds() > 20
                ? "STALE"
                : "LIVE";
    return new SessionResourceView(
        sessionId,
        toPolicy(policy),
        placement == null ? null : toAllocation(placement),
        latest == null ? null : toUsage(latest, limit),
        recent.reversed().stream().map(sample -> toPoint(sample, limit)).toList(),
        policy.status(),
        policy.getStatusReason(),
        freshness,
        policy.getLastEvaluatedAt(),
        policy.getLastAdjustedAt());
  }

  @Transactional(readOnly = true)
  public String creationOperationId(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    return events
        .findFirstBySessionIdAndEventTypeOrderByOccurredAtAsc(sessionId, "POLICY_CREATED")
        .map(SessionResourceEventEntity::getOperationId)
        .orElse(null);
  }

  @Transactional(readOnly = true)
  public ResourceEventListResponse events(
      String sessionId, String tenantId, int limit, int offset) {
    requireTenant(sessionId, tenantId);
    var safeLimit = Math.max(1, Math.min(limit, 100));
    var result =
        events
            .findBySessionIdOrderByOccurredAtDesc(
                sessionId, PageRequest.of(Math.max(0, offset) / safeLimit, safeLimit))
            .stream()
            .map(this::toEvent)
            .toList();
    return new ResourceEventListResponse(result, safeLimit, Math.max(0, offset));
  }

  @Transactional
  public SessionResourceView recordSample(String sessionId, RecordResourceSampleRequest request) {
    var placement =
        placements
            .findById(sessionId)
            .orElseThrow(() -> new ResourceTelemetryRejectedException("PLACEMENT_NOT_FOUND"));
    if (!placement.getNodeId().equals(request.nodeId())
        || "RELEASED".equals(placement.getState())) {
      throw new ResourceTelemetryRejectedException("NODE_PLACEMENT_MISMATCH");
    }
    var now = Instant.now();
    samples.save(
        new SessionResourceSampleEntity(
            newId("rs_"), sessionId, placement.getTenantId(), request, now));
    if (request.dangerEvent() != null && !request.dangerEvent().isBlank()) {
      var policy = requirePolicy(sessionId, placement.getTenantId());
      policy.evaluate(ResourcePolicyStatus.CRITICAL, request.dangerEvent(), now);
      policies.save(policy);
      appendEvent(
          sessionId,
          placement.getTenantId(),
          "DANGER_EVENT",
          request.dangerEvent(),
          null,
          null,
          "NODE_TELEMETRY",
          null,
          null,
          "PROTECTED",
          now);
    }
    return get(sessionId, placement.getTenantId());
  }

  /** Evaluate sustained windows only; a single spike never changes allocation. */
  @Transactional
  public void evaluatePolicy(String sessionId) {
    var policy = policies.findById(sessionId).orElse(null);
    if (policy == null) return;
    var now = Instant.now();
    var window =
        samples.findBySessionIdAndObservedAtAfterOrderByObservedAtAsc(
            sessionId, now.minusSeconds(Math.max(90, policy.getScaleUpWindowSeconds())));
    if (window.isEmpty()) {
      policy.evaluate(
          policy.status() == ResourcePolicyStatus.AGENT_PAUSED
              ? ResourcePolicyStatus.AGENT_PAUSED
              : ResourcePolicyStatus.OBSERVING,
          policy.status() == ResourcePolicyStatus.AGENT_PAUSED
              ? "MAXIMUM_REACHED_AGENT_PAUSED"
              : "AWAITING_RUNTIME_TELEMETRY",
          now);
      policies.save(policy);
      return;
    }
    if (window.stream().anyMatch(sample -> sample.getDangerEvent() != null)) {
      policy.evaluate(ResourcePolicyStatus.CRITICAL, "DANGER_EVENT_REPORTED", now);
      policies.save(policy);
      return;
    }
    var placement = placements.findById(sessionId).orElse(null);
    var sustainedCpu =
        sustained(window, policy.getScaleUpWindowSeconds(), s -> s.getCpuPercent(), 80d);
    var sustainedMemory =
        placement != null
            && sustained(
                window,
                Math.max(90, policy.getScaleUpWindowSeconds()),
                s ->
                    s.getMemoryRssMib() == null
                        ? null
                        : s.getMemoryRssMib() * 100d / placement.getMemoryLimitMib(),
                75d);
    if (!sustainedCpu && !sustainedMemory) {
      policy.evaluate(ResourcePolicyStatus.STABLE, "WINDOW_WITHIN_POLICY", now);
      policies.save(policy);
      return;
    }
    var atMaximum =
        placement != null
            && (placement.getCpuMillis() >= policy.getMaximumCpuMillis()
                || placement.getMemoryLimitMib() >= policy.getMaximumMemoryMib());
    if (atMaximum && policy.onMaximumReached() == MaximumReachedPolicy.PAUSE_AGENT) {
      var newlyPaused = policy.status() != ResourcePolicyStatus.AGENT_PAUSED;
      tasks
          .findAllBySessionIdAndState(sessionId, "RUNNING")
          .forEach(
              task -> {
                task.pauseByResourcePolicy(now);
                tasks.save(task);
              });
      policy.evaluate(ResourcePolicyStatus.AGENT_PAUSED, "MAXIMUM_REACHED_AGENT_PAUSED", now);
      if (newlyPaused) {
        appendEvent(
            sessionId,
            policy.getTenantId(),
            "MAXIMUM_REACHED",
            "PAUSE_AGENT_PRESERVE_BROWSER",
            null,
            null,
            "RESOURCE_DECISION_ENGINE",
            null,
            null,
            "COMMITTED",
            now);
      }
    } else {
      // Allocation is unchanged until a real Node actuator acknowledges it.
      policy.evaluate(
          atMaximum ? ResourcePolicyStatus.AT_MAXIMUM : ResourcePolicyStatus.OBSERVING,
          atMaximum ? "MAXIMUM_REACHED" : "SUSTAINED_PRESSURE_AWAITING_ACTUATOR",
          now);
    }
    policies.save(policy);
  }

  @Transactional
  public void protectFromNodePressure(String sessionId, String tenantId, String nodeId) {
    var policy = requirePolicy(sessionId, tenantId);
    var now = Instant.now();
    tasks
        .findAllBySessionIdAndState(sessionId, "RUNNING")
        .forEach(
            task -> {
              task.pauseByResourcePolicy(now);
              tasks.save(task);
            });
    policy.evaluate(
        ResourcePolicyStatus.WAITING_SAFE_POINT, "NODE_PRESSURE_BROWSER_PRESERVED", now);
    policies.save(policy);
    appendEvent(
        sessionId,
        tenantId,
        "NODE_PRESSURE_PROTECTION",
        "AGENT_PAUSED_WAITING_SAFE_POINT:" + nodeId,
        null,
        null,
        "NODE_PRESSURE_GOVERNOR",
        null,
        null,
        "BROWSER_PRESERVED",
        now);
  }

  private SessionContext requireTenant(String sessionId, String tenantId) {
    var session = sessions.require(sessionId);
    if (!session.tenantId().equals(tenantId)) throw new ResourcePolicyNotFoundException();
    return session;
  }

  private SessionResourcePolicyEntity requirePolicy(String sessionId, String tenantId) {
    return policies
        .findBySessionIdAndTenantId(sessionId, tenantId)
        .orElseThrow(ResourcePolicyNotFoundException::new);
  }

  private boolean sustained(
      List<SessionResourceSampleEntity> values,
      int durationSeconds,
      java.util.function.Function<SessionResourceSampleEntity, Double> metric,
      double threshold) {
    var qualifying = values.stream().filter(value -> metric.apply(value) != null).toList();
    return qualifying.size() >= 2
        && Duration.between(
                    qualifying.getFirst().getObservedAt(), qualifying.getLast().getObservedAt())
                .toSeconds()
            >= durationSeconds
        && percentile(qualifying.stream().map(metric).sorted().toList(), 0.95) > threshold
        && ewma(qualifying.stream().map(metric).toList(), 0.35) > threshold;
  }

  private static double percentile(List<Double> values, double quantile) {
    return values.get(Math.min(values.size() - 1, (int) Math.ceil(values.size() * quantile) - 1));
  }

  private static double ewma(List<Double> values, double alpha) {
    double result = values.getFirst();
    for (int index = 1; index < values.size(); index++) {
      result = alpha * values.get(index) + (1 - alpha) * result;
    }
    return result;
  }

  private PolicyView toPolicy(SessionResourcePolicyEntity policy) {
    return new PolicyView(
        policy.mode(),
        policy.executionEnvironment(),
        policy.getMinimumTemplate(),
        policy.getResolvedTemplate(),
        policy.getMaximumCpuMillis(),
        policy.getMaximumMemoryMib(),
        policy.getMaximumCostPerHour(),
        policy.getScaleUpWindowSeconds(),
        policy.getScaleDownWindowSeconds(),
        policy.getAdjustmentCooldownSeconds(),
        policy.isAllowMigration(),
        policy.isAllowHibernate(),
        policy.isBlockMigrationDuringHumanTakeover(),
        policy.onMaximumReached());
  }

  private AllocationView toAllocation(BrowserPlacementEntity placement) {
    return new AllocationView(
        placement.getNodeId(),
        templateFor(placement.effectiveResourceClass()),
        placement.getCpuMillis(),
        placement.getMemoryRequestMib(),
        placement.getMemoryLimitMib(),
        placement.getTabBudget(),
        placement.getState());
  }

  private UsageView toUsage(SessionResourceSampleEntity sample, Integer memoryLimit) {
    return new UsageView(
        sample.getCpuPercent(),
        sample.getMemoryRssMib(),
        memoryLimit == null || sample.getMemoryRssMib() == null
            ? null
            : sample.getMemoryRssMib() * 100d / memoryLimit,
        sample.getMemoryPsiSomeAvg10(),
        sample.getRendererCount(),
        sample.getTabCount(),
        sample.getAgentActionLatencyMs(),
        sample.getStateDiffQueueDepth(),
        sample.getRemoteDesktopFrameAgeMs(),
        sample.getMediaEncoderPercent(),
        sample.getObservedAt());
  }

  private UsagePoint toPoint(SessionResourceSampleEntity sample, Integer memoryLimit) {
    return new UsagePoint(
        sample.getObservedAt(),
        sample.getCpuPercent(),
        sample.getMemoryRssMib(),
        memoryLimit == null || sample.getMemoryRssMib() == null
            ? null
            : sample.getMemoryRssMib() * 100d / memoryLimit);
  }

  private ResourceEventView toEvent(SessionResourceEventEntity event) {
    return new ResourceEventView(
        event.getEventId(),
        event.getOccurredAt(),
        event.getEventType(),
        event.getReason(),
        readMap(event.getOldResources()),
        readMap(event.getNewResources()),
        event.getDecisionSource(),
        event.getOperationId(),
        event.getRequestId(),
        event.getResult());
  }

  private void appendEvent(
      String sessionId,
      String tenantId,
      String type,
      String reason,
      Map<String, Object> oldResources,
      Map<String, Object> newResources,
      String source,
      String operationId,
      String requestId,
      String result,
      Instant now) {
    events.save(
        new SessionResourceEventEntity(
            newId("re_"),
            sessionId,
            tenantId,
            type,
            reason == null || reason.isBlank() ? "NO_ADDITIONAL_REASON" : reason,
            writeMap(oldResources),
            writeMap(newResources),
            source,
            operationId,
            requestId,
            result,
            now));
  }

  private Map<String, Object> policyMap(SessionResourcePolicyEntity policy) {
    return mapper.convertValue(toPolicy(policy), new TypeReference<>() {});
  }

  private Map<String, Object> allocationMap(BrowserPlacementView placement) {
    return Map.of(
        "template", templateFor(placement.effectiveResourceClass()),
        "cpuMillis", placement.cpuMillis(),
        "memoryLimitMib", placement.memoryLimitMib(),
        "nodeId", placement.nodeId());
  }

  private String writeMap(Map<String, Object> value) {
    if (value == null) return null;
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private Map<String, Object> readMap(String value) {
    if (value == null) return null;
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String templateFor(ResourceClass resourceClass) {
    return switch (resourceClass) {
      case L0, L1, L2 -> "standard-v1";
      case L3 -> "interactive-v1";
      case L4 -> "heavy-v1";
      case L5 -> "native-standard-v1";
    };
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  public static final class ResourcePolicyNotFoundException extends RuntimeException {}

  public static final class ResourcePolicyPermissionException extends RuntimeException {}

  public static final class ResourceTelemetryRejectedException extends RuntimeException {
    public ResourceTelemetryRejectedException(String message) {
      super(message);
    }
  }
}
