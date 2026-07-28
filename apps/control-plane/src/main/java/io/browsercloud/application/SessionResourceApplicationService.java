package io.browsercloud.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.BrowserPlacementView;
import io.browsercloud.api.ResourcePolicyRequest;
import io.browsercloud.api.SessionResourceModels.*;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.OperationFactory;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.capacity.RuntimeResourceLimits;
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
  private static final double MEMORY_PSI_SCALE_UP = 5.0;
  private static final double MAIN_THREAD_BLOCKED_SCALE_UP_MS = 1_000.0;
  private static final double AGENT_ACTION_LATENCY_SCALE_UP_MS = 1_500.0;
  private static final double STATE_DIFF_QUEUE_SCALE_UP = 100.0;
  private static final double PROFILE_IO_SCALE_UP_BYTES_PER_SECOND = 50.0 * 1024 * 1024;
  private static final double EXTENSION_CPU_SCALE_UP = 70.0;
  private static final double EXTENSION_MEMORY_SCALE_UP_MIB = 512.0;
  private static final double REMOTE_DESKTOP_FRAME_AGE_SCALE_UP_MS = 1_000.0;
  private static final double MEDIA_ENCODER_SCALE_UP = 85.0;

  private final SessionResourcePolicyJpaRepository policies;
  private final SessionResourceSampleJpaRepository samples;
  private final SessionResourceEventJpaRepository events;
  private final BrowserPlacementJpaRepository placements;
  private final AgentTaskJpaRepository tasks;
  private final SessionRepository sessions;
  private final OperationRepository operations;
  private final IdempotencyService idempotency;
  private final NodeCommandGateway nodeCommandGateway;
  private final SafePointApplicationService safePointService;
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
      NodeCommandGateway nodeCommandGateway,
      SafePointApplicationService safePointService,
      ObjectMapper mapper) {
    this.policies = policies;
    this.samples = samples;
    this.events = events;
    this.placements = placements;
    this.tasks = tasks;
    this.sessions = sessions;
    this.operations = operations;
    this.idempotency = idempotency;
    this.nodeCommandGateway = nodeCommandGateway;
    this.safePointService = safePointService;
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

  @Transactional
  public SessionResourceView recordSampleFromNode(
      String sessionId, String tenantId, long contextEpoch, RecordResourceSampleRequest request) {
    var session = requireTenant(sessionId, tenantId);
    if (session.contextEpoch() != contextEpoch) {
      throw new ResourceTelemetryRejectedException("STALE_RESOURCE_CONTEXT");
    }
    return recordSample(sessionId, request);
  }

  /** Evaluate sustained windows only; a single spike never changes allocation. */
  @Transactional
  public void evaluatePolicy(String sessionId) {
    var policy = policies.findById(sessionId).orElse(null);
    if (policy == null) return;
    var now = Instant.now();
    var window =
        samples.findBySessionIdAndObservedAtAfterOrderByObservedAtAsc(
            sessionId,
            now.minusSeconds(
                Math.max(policy.getScaleDownWindowSeconds(), policy.getScaleUpWindowSeconds())));
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
    if (hasDangerEvent(window)) {
      policy.evaluate(ResourcePolicyStatus.CRITICAL, "DANGER_EVENT_REPORTED", now);
      policies.save(policy);
      return;
    }
    var placement = placements.findById(sessionId).orElse(null);
    if (placement == null || !"ACTIVE".equals(placement.getState())) {
      policy.evaluate(ResourcePolicyStatus.OBSERVING, "ACTIVE_PLACEMENT_REQUIRED", now);
      policies.save(policy);
      return;
    }
    var pressureReason = scaleUpPressureReason(window, policy, placement);
    var cooldownActive =
        policy.getLastAdjustedAt() != null
            && policy
                .getLastAdjustedAt()
                .plusSeconds(policy.getAdjustmentCooldownSeconds())
                .isAfter(now);
    if (pressureReason != null) {
      if (cooldownActive) {
        policy.evaluate(ResourcePolicyStatus.OBSERVING, "ADJUSTMENT_COOLDOWN_ACTIVE", now);
        policies.save(policy);
        return;
      }
      var atMaximum =
          placement.getCpuMillis() >= policy.getMaximumCpuMillis()
              && placement.getMemoryLimitMib() >= policy.getMaximumMemoryMib();
      if (atMaximum) {
        handleMaximumReached(policy, sessionId, now);
        policies.save(policy);
        return;
      }
      requestAdjustment(
          requireTenant(sessionId, policy.getTenantId()),
          policy,
          placement,
          Math.min(
              policy.getMaximumCpuMillis(),
              Math.max(placement.getCpuMillis() + 250, placement.getCpuMillis() * 3 / 2)),
          Math.min(
              policy.getMaximumMemoryMib(),
              Math.max(
                  placement.getMemoryRequestMib() + 256, placement.getMemoryRequestMib() * 3 / 2)),
          Math.min(
              policy.getMaximumMemoryMib(),
              Math.max(placement.getMemoryLimitMib() + 512, placement.getMemoryLimitMib() * 3 / 2)),
          ResourcePolicyStatus.SCALING_UP,
          pressureReason,
          now);
      return;
    }

    var sustainedLowCpu =
        sustainedBelow(window, policy.getScaleDownWindowSeconds(), s -> s.getCpuPercent(), 25d);
    var sustainedLowMemory =
        sustainedBelow(
            window,
            policy.getScaleDownWindowSeconds(),
            s ->
                s.getMemoryRssMib() == null
                    ? null
                    : s.getMemoryRssMib() * 100d / placement.getMemoryLimitMib(),
            40d);
    var baselineCpu = 600;
    var baselineRequest = 768;
    var baselineLimit = 1280;
    var secondaryLoadPresent = hasSecondaryLoadInScaleDownWindow(window, policy, placement, now);
    if (sustainedLowCpu
        && sustainedLowMemory
        && !cooldownActive
        && !secondaryLoadPresent
        && (placement.getCpuMillis() > baselineCpu
            || placement.getMemoryLimitMib() > baselineLimit)) {
      requestAdjustment(
          requireTenant(sessionId, policy.getTenantId()),
          policy,
          placement,
          Math.max(baselineCpu, placement.getCpuMillis() * 3 / 4),
          Math.max(baselineRequest, placement.getMemoryRequestMib() * 3 / 4),
          Math.max(baselineLimit, placement.getMemoryLimitMib() * 3 / 4),
          ResourcePolicyStatus.SCALING_DOWN,
          "SUSTAINED_LOW_LOAD",
          now);
    } else {
      var reason =
          cooldownActive
              ? "ADJUSTMENT_COOLDOWN_ACTIVE"
              : secondaryLoadPresent
                  ? "SECONDARY_LOAD_WITHIN_SCALE_DOWN_WINDOW"
                  : "WINDOW_WITHIN_POLICY";
      policy.evaluate(
          cooldownActive || secondaryLoadPresent
              ? ResourcePolicyStatus.OBSERVING
              : ResourcePolicyStatus.STABLE,
          reason,
          now);
      policies.save(policy);
    }
  }

  private void handleMaximumReached(
      SessionResourcePolicyEntity policy, String sessionId, Instant now) {
    switch (policy.onMaximumReached()) {
      case PAUSE_AGENT -> {
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
      }
      case WAIT_SAFE_POINT_MIGRATE -> {
        pauseAgentTasks(sessionId, now);
        if (!policy.isAllowMigration()) {
          policy.evaluate(
              ResourcePolicyStatus.AGENT_PAUSED, "MAXIMUM_REACHED_MIGRATION_DISABLED", now);
          break;
        }
        var safePoint = safePointService.assess(sessionId, policy.getTenantId());
        var reason =
            safePoint.safe()
                ? "SAFE_POINT_READY_MIGRATION_DISPATCH_PENDING"
                : "MAXIMUM_REACHED_WAIT_SAFE_POINT:" + safePoint.blockers().getFirst().code();
        var changed = !reason.equals(policy.getStatusReason());
        policy.evaluate(ResourcePolicyStatus.WAITING_SAFE_POINT, reason, now);
        if (changed) {
          appendEvent(
              sessionId,
              policy.getTenantId(),
              safePoint.safe() ? "SAFE_POINT_READY" : "SAFE_POINT_BLOCKED",
              reason,
              null,
              null,
              "SAFE_POINT_AGGREGATOR",
              null,
              null,
              safePoint.safe() ? "MIGRATION_PENDING" : "BROWSER_PRESERVED",
              now);
        }
      }
      case HIBERNATE -> {
        pauseAgentTasks(sessionId, now);
        if (!policy.isAllowHibernate()) {
          policy.evaluate(
              ResourcePolicyStatus.AGENT_PAUSED, "MAXIMUM_REACHED_HIBERNATE_DISABLED", now);
          break;
        }
        var safePoint = safePointService.assess(sessionId, policy.getTenantId());
        var status =
            safePoint.safe()
                ? ResourcePolicyStatus.HIBERNATING
                : ResourcePolicyStatus.WAITING_SAFE_POINT;
        var reason =
            safePoint.safe()
                ? "SAFE_POINT_READY_HIBERNATE_DISPATCH_PENDING"
                : "MAXIMUM_REACHED_HIBERNATE_WAIT_SAFE_POINT:"
                    + safePoint.blockers().getFirst().code();
        var changed = status != policy.status() || !reason.equals(policy.getStatusReason());
        policy.evaluate(status, reason, now);
        if (changed) {
          appendEvent(
              sessionId,
              policy.getTenantId(),
              safePoint.safe() ? "SAFE_POINT_READY" : "SAFE_POINT_BLOCKED",
              reason,
              null,
              null,
              "SAFE_POINT_AGGREGATOR",
              null,
              null,
              safePoint.safe() ? "HIBERNATE_PENDING" : "BROWSER_PRESERVED",
              now);
        }
      }
      case TERMINATE_STRICT ->
          policy.evaluate(
              ResourcePolicyStatus.CRITICAL, "MAXIMUM_REACHED_STRICT_TERMINATION_REQUIRED", now);
    }
  }

  private void pauseAgentTasks(String sessionId, Instant now) {
    tasks
        .findAllBySessionIdAndState(sessionId, "RUNNING")
        .forEach(
            task -> {
              task.pauseByResourcePolicy(now);
              tasks.save(task);
            });
  }

  @Transactional
  public void maximumActionDispatched(
      String sessionId, String action, String operationId, String result) {
    var policy = policies.findById(sessionId).orElseThrow(ResourcePolicyNotFoundException::new);
    var now = Instant.now();
    policy.evaluate(
        "HIBERNATE".equals(action)
            ? ResourcePolicyStatus.HIBERNATING
            : ResourcePolicyStatus.CRITICAL,
        action + "_OPERATION_DISPATCHED:" + operationId,
        now);
    policies.save(policy);
    appendEvent(
        sessionId,
        policy.getTenantId(),
        "MAXIMUM_ACTION_DISPATCHED",
        action,
        null,
        null,
        "RESOURCE_DECISION_ENGINE",
        operationId,
        null,
        result,
        now);
  }

  @Transactional
  public void recordMigrationPhase(
      String sessionId,
      String migrationId,
      String phase,
      String result,
      boolean completed,
      boolean ready) {
    var policy = policies.findById(sessionId).orElseThrow(ResourcePolicyNotFoundException::new);
    var now = Instant.now();
    policy.evaluate(
        completed
            ? (ready ? ResourcePolicyStatus.STABLE : ResourcePolicyStatus.CRITICAL)
            : ResourcePolicyStatus.MIGRATING,
        "MIGRATION_" + phase + ":" + result,
        now);
    policies.save(policy);
    if (completed && ready) {
      tasks
          .findAllBySessionIdAndState(sessionId, "PAUSED_BY_RESOURCE_POLICY")
          .forEach(
              task -> {
                task.resumeAfterResourceRecovery(now);
                tasks.save(task);
              });
    }
    appendEvent(
        sessionId,
        policy.getTenantId(),
        "MIGRATION_" + phase,
        result,
        null,
        null,
        "SESSION_MIGRATION_WORKFLOW",
        migrationId,
        null,
        completed ? (ready ? "COMMITTED" : "DEGRADED") : "IN_PROGRESS",
        now);
  }

  private void requestAdjustment(
      SessionContext session,
      SessionResourcePolicyEntity policy,
      BrowserPlacementEntity placement,
      int cpuMillis,
      int memoryRequestMib,
      int memoryLimitMib,
      ResourcePolicyStatus status,
      String reason,
      Instant now) {
    if (operations.findActive(session.sessionId()).isPresent()) {
      policy.evaluate(ResourcePolicyStatus.OBSERVING, "RESOURCE_ADJUSTMENT_OPERATION_BUSY", now);
      policies.save(policy);
      return;
    }
    memoryRequestMib = Math.min(memoryRequestMib, memoryLimitMib);
    var scalingUp = status == ResourcePolicyStatus.SCALING_UP;
    var stateCollectorBudgetPercent =
        scalingUp
            ? Math.min(100, placement.getStateCollectorBudgetPercent() + 25)
            : Math.max(25, placement.getStateCollectorBudgetPercent() - 25);
    var remoteDesktopBitrateKbps =
        !placement.isRequiresDesktop()
            ? 0
            : scalingUp
                ? Math.min(
                    12_000,
                    Math.max(
                        1_000,
                        Math.max(
                            placement.getRemoteDesktopBitrateKbps() + 500,
                            placement.getRemoteDesktopBitrateKbps() * 3 / 2)))
                : Math.max(750, placement.getRemoteDesktopBitrateKbps() * 3 / 4);
    var extensionIds = readExtensionIds(placement.getExtensionIds());
    var extensionCpuWeight =
        extensionIds.isEmpty()
            ? placement.getExtensionCpuWeight()
            : scalingUp
                ? Math.min(
                    1_000,
                    Math.max(
                        placement.getExtensionCpuWeight() + 50,
                        placement.getExtensionCpuWeight() * 3 / 2))
                : Math.max(25, placement.getExtensionCpuWeight() * 3 / 4);
    var mediaEncoderSlots =
        !placement.isRequiresMedia()
            ? 0
            : scalingUp
                ? Math.min(placement.getMediaSlots(), placement.getMediaEncoderSlots() + 1)
                : Math.max(1, placement.getMediaEncoderSlots() - 1);
    var operationId = newId("op_");
    var operation =
        OperationFactory.resourceAdjustment(
            session, operations.nextOperationEpoch(session.sessionId()), operationId);
    var limits =
        new RuntimeResourceLimits(
            placement.effectiveResourceClass(),
            cpuMillis,
            memoryRequestMib,
            memoryLimitMib,
            placement.getPidLimit(),
            placement.getTabBudget(),
            stateCollectorBudgetPercent,
            remoteDesktopBitrateKbps,
            extensionIds,
            extensionCpuWeight,
            mediaEncoderSlots,
            placement.isRequiresDesktop(),
            placement.isRequiresGpu(),
            placement.isRequiresNativeOs(),
            placement.isRequiresIsolation());
    operations.insert(operation);
    nodeCommandGateway.send(
        NodeCommands.adjustRuntimeResources(session, operation, limits, reason));
    policy.evaluate(status, reason + "_COMMAND_DISPATCHED", now);
    policies.save(policy);
    appendEvent(
        session.sessionId(),
        session.tenantId(),
        "ADJUSTMENT_REQUESTED",
        reason,
        allocationMap(placement),
        allocationMap(
            placement,
            cpuMillis,
            memoryRequestMib,
            memoryLimitMib,
            stateCollectorBudgetPercent,
            remoteDesktopBitrateKbps,
            extensionCpuWeight,
            mediaEncoderSlots),
        "RESOURCE_DECISION_ENGINE",
        operationId,
        null,
        "PENDING_NODE_ACK",
        now);
  }

  @Transactional
  public void recordAdjustmentAcknowledged(
      String tenantId, NodeEvent.RuntimeResourcesAdjusted adjusted) {
    var session = requireTenant(adjusted.sessionId(), tenantId);
    var placement =
        placements
            .findById(adjusted.sessionId())
            .orElseThrow(() -> new ResourceTelemetryRejectedException("PLACEMENT_NOT_FOUND"));
    if (!placement.getNodeId().equals(adjusted.nodeId())
        || !placement.effectiveResourceClass().name().equals(adjusted.oldResourceClass())
        || !placement.effectiveResourceClass().name().equals(adjusted.newResourceClass())
        || placement.getCpuMillis() != adjusted.oldCpuMillis()
        || placement.getMemoryRequestMib() != adjusted.oldMemoryRequestMib()
        || placement.getMemoryLimitMib() != adjusted.oldMemoryLimitMib()
        || placement.getPidLimit() != adjusted.oldPidLimit()
        || placement.getTabBudget() != adjusted.oldTabBudget()
        || placement.getPidLimit() != adjusted.newPidLimit()
        || placement.getTabBudget() != adjusted.newTabBudget()
        || (adjusted.oldStateCollectorBudgetPercent() != null
            && placement.getStateCollectorBudgetPercent()
                != adjusted.oldStateCollectorBudgetPercent())
        || (adjusted.oldRemoteDesktopBitrateKbps() != null
            && placement.getRemoteDesktopBitrateKbps() != adjusted.oldRemoteDesktopBitrateKbps())
        || (adjusted.oldExtensionCpuWeight() != null
            && placement.getExtensionCpuWeight() != adjusted.oldExtensionCpuWeight())
        || (adjusted.oldMediaEncoderSlots() != null
            && placement.getMediaEncoderSlots() != adjusted.oldMediaEncoderSlots())) {
      throw new ResourceTelemetryRejectedException("RESOURCE_ADJUSTMENT_ACK_MISMATCH");
    }
    var nextStateCollectorBudgetPercent =
        adjusted.newStateCollectorBudgetPercent() == null
            ? placement.getStateCollectorBudgetPercent()
            : adjusted.newStateCollectorBudgetPercent();
    var nextRemoteDesktopBitrateKbps =
        adjusted.newRemoteDesktopBitrateKbps() == null
            ? placement.getRemoteDesktopBitrateKbps()
            : adjusted.newRemoteDesktopBitrateKbps();
    var nextExtensionCpuWeight =
        adjusted.newExtensionCpuWeight() == null
            ? placement.getExtensionCpuWeight()
            : adjusted.newExtensionCpuWeight();
    var nextMediaEncoderSlots =
        adjusted.newMediaEncoderSlots() == null
            ? placement.getMediaEncoderSlots()
            : adjusted.newMediaEncoderSlots();
    var policy = requirePolicy(adjusted.sessionId(), tenantId);
    if (adjusted.newCpuMillis() <= 0
        || adjusted.newCpuMillis() > policy.getMaximumCpuMillis()
        || adjusted.newMemoryRequestMib() <= 0
        || adjusted.newMemoryLimitMib() < adjusted.newMemoryRequestMib()
        || adjusted.newMemoryLimitMib() > policy.getMaximumMemoryMib()
        || nextStateCollectorBudgetPercent < 10
        || nextStateCollectorBudgetPercent > 100
        || nextRemoteDesktopBitrateKbps < 0
        || nextRemoteDesktopBitrateKbps > 100_000
        || (placement.isRequiresDesktop() && nextRemoteDesktopBitrateKbps < 250)
        || (!placement.isRequiresDesktop() && nextRemoteDesktopBitrateKbps != 0)) {
      throw new ResourceTelemetryRejectedException("RESOURCE_ADJUSTMENT_ACK_OUT_OF_POLICY");
    }
    if (nextExtensionCpuWeight < 1 || nextExtensionCpuWeight > 10_000) {
      throw new ResourceTelemetryRejectedException("RESOURCE_ADJUSTMENT_ACK_OUT_OF_POLICY");
    }
    if ((!placement.isRequiresMedia() && nextMediaEncoderSlots != 0)
        || (placement.isRequiresMedia()
            && (nextMediaEncoderSlots < 1 || nextMediaEncoderSlots > placement.getMediaSlots()))) {
      throw new ResourceTelemetryRejectedException("RESOURCE_ADJUSTMENT_ACK_OUT_OF_POLICY");
    }
    var old = allocationMap(placement);
    placement.applyResourceAdjustment(
        adjusted.newCpuMillis(),
        adjusted.newMemoryRequestMib(),
        adjusted.newMemoryLimitMib(),
        adjusted.newPidLimit(),
        adjusted.newTabBudget(),
        nextStateCollectorBudgetPercent,
        nextRemoteDesktopBitrateKbps,
        nextExtensionCpuWeight,
        nextMediaEncoderSlots);
    placements.save(placement);
    var now = Instant.now();
    var template =
        adjusted.newMemoryLimitMib() > 2048 || adjusted.newCpuMillis() > 2000
            ? "heavy-v1"
            : adjusted.newMemoryLimitMib() > 1280 || adjusted.newCpuMillis() > 600
                ? "interactive-v1"
                : "standard-v1";
    policy.adjustmentCommitted(
        ResourcePolicyStatus.STABLE, "NODE_ACTUATOR_ACKNOWLEDGED", template, now);
    policies.save(policy);
    appendEvent(
        session.sessionId(),
        tenantId,
        "ALLOCATION_ADJUSTED",
        adjusted.reason(),
        old,
        allocationMap(placement),
        "NODE_RESOURCE_ACTUATOR",
        adjusted.operationId(),
        null,
        "COMMITTED",
        now);
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

  String scaleUpPressureReason(
      List<SessionResourceSampleEntity> window,
      SessionResourcePolicyEntity policy,
      BrowserPlacementEntity placement) {
    var scaleUpWindow = policy.getScaleUpWindowSeconds();
    if (sustained(window, scaleUpWindow, SessionResourceSampleEntity::getCpuPercent, 80d)) {
      return "SUSTAINED_CPU_PRESSURE";
    }
    if (sustained(
        window,
        Math.max(90, scaleUpWindow),
        sample ->
            sample.getMemoryRssMib() == null
                ? null
                : sample.getMemoryRssMib() * 100d / placement.getMemoryLimitMib(),
        75d)) {
      return "SUSTAINED_MEMORY_PRESSURE";
    }
    if (sustained(
        window,
        scaleUpWindow,
        SessionResourceSampleEntity::getMemoryPsiSomeAvg10,
        MEMORY_PSI_SCALE_UP)) {
      return "SUSTAINED_MEMORY_PSI_PRESSURE";
    }
    if (sustained(
        window, scaleUpWindow, sample -> number(sample.getTabCount()), placement.getTabBudget())) {
      return "SUSTAINED_TAB_BUDGET_PRESSURE";
    }
    if (sustained(
        window,
        scaleUpWindow,
        sample -> number(sample.getRendererCount()),
        Math.max(8d, placement.getTabBudget() * 3d))) {
      return "SUSTAINED_RENDERER_PRESSURE";
    }
    if (sustained(
        window,
        scaleUpWindow,
        sample -> number(sample.getMainThreadBlockedMs()),
        MAIN_THREAD_BLOCKED_SCALE_UP_MS)) {
      return "SUSTAINED_MAIN_THREAD_PRESSURE";
    }
    if (sustained(
        window,
        scaleUpWindow,
        sample -> number(sample.getAgentActionLatencyMs()),
        AGENT_ACTION_LATENCY_SCALE_UP_MS)) {
      return "SUSTAINED_AGENT_ACTION_LATENCY";
    }
    if (sustained(
        window,
        scaleUpWindow,
        sample -> number(sample.getStateDiffQueueDepth()),
        STATE_DIFF_QUEUE_SCALE_UP)) {
      return "SUSTAINED_STATE_DIFF_BACKLOG";
    }
    if (sustained(
        window,
        scaleUpWindow,
        sample -> number(sample.getProfileIoBytesPerSecond()),
        PROFILE_IO_SCALE_UP_BYTES_PER_SECOND)) {
      return "SUSTAINED_PROFILE_IO_PRESSURE";
    }
    if (sustained(
        window,
        scaleUpWindow,
        SessionResourceSampleEntity::getExtensionCpuPercent,
        EXTENSION_CPU_SCALE_UP)) {
      return "SUSTAINED_EXTENSION_CPU_PRESSURE";
    }
    if (sustained(
        window,
        scaleUpWindow,
        sample -> number(sample.getExtensionMemoryMib()),
        EXTENSION_MEMORY_SCALE_UP_MIB)) {
      return "SUSTAINED_EXTENSION_MEMORY_PRESSURE";
    }
    if (sustained(
        window,
        scaleUpWindow,
        sample -> number(sample.getRemoteDesktopFrameAgeMs()),
        REMOTE_DESKTOP_FRAME_AGE_SCALE_UP_MS)) {
      return "SUSTAINED_REMOTE_DESKTOP_FRAME_AGE";
    }
    if (sustained(
        window,
        scaleUpWindow,
        SessionResourceSampleEntity::getMediaEncoderPercent,
        MEDIA_ENCODER_SCALE_UP)) {
      return "SUSTAINED_MEDIA_ENCODER_PRESSURE";
    }
    return null;
  }

  boolean hasDangerEvent(List<SessionResourceSampleEntity> window) {
    return window.stream()
        .anyMatch(sample -> sample.getDangerEvent() != null && !sample.getDangerEvent().isBlank());
  }

  private static Double number(Number value) {
    return value == null ? null : value.doubleValue();
  }

  boolean hasSecondaryLoadInScaleDownWindow(
      List<SessionResourceSampleEntity> window,
      SessionResourcePolicyEntity policy,
      BrowserPlacementEntity placement,
      Instant now) {
    var duration = policy.getScaleDownWindowSeconds();
    return recentAbove(
            window, duration, now, SessionResourceSampleEntity::getMemoryPsiSomeAvg10, 1.0)
        || recentAbove(window, duration, now, sample -> number(sample.getTabCount()), 4.0)
        || recentAbove(
            window,
            duration,
            now,
            sample -> number(sample.getRendererCount()),
            Math.max(6d, placement.getTabBudget() * 2d))
        || recentAbove(
            window, duration, now, sample -> number(sample.getMainThreadBlockedMs()), 500.0)
        || recentAbove(
            window, duration, now, sample -> number(sample.getAgentActionLatencyMs()), 750.0)
        || recentAbove(
            window, duration, now, sample -> number(sample.getStateDiffQueueDepth()), 20.0)
        || recentAbove(
            window,
            duration,
            now,
            sample -> number(sample.getProfileIoBytesPerSecond()),
            20.0 * 1024 * 1024)
        || recentAbove(
            window, duration, now, SessionResourceSampleEntity::getExtensionCpuPercent, 35.0)
        || recentAbove(
            window, duration, now, sample -> number(sample.getExtensionMemoryMib()), 256.0)
        || recentAbove(
            window, duration, now, sample -> number(sample.getRemoteDesktopFrameAgeMs()), 500.0)
        || recentAbove(
            window, duration, now, SessionResourceSampleEntity::getMediaEncoderPercent, 60.0);
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

  private boolean sustainedBelow(
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
        && percentile(qualifying.stream().map(metric).sorted().toList(), 0.95) < threshold
        && ewma(qualifying.stream().map(metric).toList(), 0.35) < threshold;
  }

  private boolean recentAbove(
      List<SessionResourceSampleEntity> values,
      int durationSeconds,
      Instant now,
      java.util.function.Function<SessionResourceSampleEntity, Double> metric,
      double threshold) {
    var cutoff = now.minusSeconds(durationSeconds);
    return values.stream()
        .filter(value -> !value.getObservedAt().isBefore(cutoff))
        .map(metric)
        .filter(Objects::nonNull)
        .anyMatch(value -> value > threshold);
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
        placement.getStateCollectorBudgetPercent(),
        placement.getRemoteDesktopBitrateKbps(),
        placement.getExtensionCpuWeight(),
        placement.getMediaEncoderSlots(),
        placement.getMediaSlots(),
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
        sample.getProfileIoBytesPerSecond(),
        sample.getExtensionCpuPercent(),
        sample.getExtensionMemoryMib(),
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
        "stateCollectorBudgetPercent", placement.stateCollectorBudgetPercent(),
        "remoteDesktopBitrateKbps", placement.remoteDesktopBitrateKbps(),
        "extensionCpuWeight", placement.extensionCpuWeight(),
        "mediaEncoderSlots", placement.mediaEncoderSlots(),
        "mediaEncoderSlotLimit", placement.mediaSlots(),
        "nodeId", placement.nodeId());
  }

  private Map<String, Object> allocationMap(BrowserPlacementEntity placement) {
    return allocationMap(
        placement,
        placement.getCpuMillis(),
        placement.getMemoryRequestMib(),
        placement.getMemoryLimitMib(),
        placement.getStateCollectorBudgetPercent(),
        placement.getRemoteDesktopBitrateKbps(),
        placement.getExtensionCpuWeight(),
        placement.getMediaEncoderSlots());
  }

  private Map<String, Object> allocationMap(
      BrowserPlacementEntity placement,
      int cpuMillis,
      int memoryRequestMib,
      int memoryLimitMib,
      int stateCollectorBudgetPercent,
      int remoteDesktopBitrateKbps,
      int extensionCpuWeight,
      int mediaEncoderSlots) {
    return Map.of(
        "template", templateFor(placement.effectiveResourceClass()),
        "cpuMillis", cpuMillis,
        "memoryRequestMib", memoryRequestMib,
        "memoryLimitMib", memoryLimitMib,
        "stateCollectorBudgetPercent", stateCollectorBudgetPercent,
        "remoteDesktopBitrateKbps", remoteDesktopBitrateKbps,
        "extensionCpuWeight", extensionCpuWeight,
        "mediaEncoderSlots", mediaEncoderSlots,
        "mediaEncoderSlotLimit", placement.getMediaSlots(),
        "nodeId", placement.getNodeId());
  }

  private List<String> readExtensionIds(String value) {
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (Exception exception) {
      throw new IllegalStateException("Placement extension IDs are invalid", exception);
    }
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
