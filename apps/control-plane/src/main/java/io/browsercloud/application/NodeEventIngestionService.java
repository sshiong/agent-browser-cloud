package io.browsercloud.application;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.CoordinatorResult;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.persistence.InboxEventEntity;
import io.browsercloud.persistence.InboxEventJpaRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在单一事务内完成 Node Event Inbox 去重和 Coordinator 状态提交。 */
@Service
public class NodeEventIngestionService {

  static final String CONSUMER_ID = "session-coordinator-v1";

  private final InboxEventJpaRepository inboxRepository;
  private final SessionCoordinator coordinator;
  private final BrowserStateRepository browserStateRepository;
  private final ProfileApplicationService profileApplicationService;
  private final StaticProxyApplicationService proxyApplicationService;
  private final SessionRepository sessionRepository;
  private final NodeCommandGateway nodeCommandGateway;
  private final AgentNavigationCompletionService agentNavigationCompletionService;
  private final AuditApplicationService auditService;
  private final DurableWorkflowApplicationService workflowService;
  private final BrowserCapacityApplicationService browserCapacityService;
  private final SessionResourceApplicationService resourceService;
  private final BusinessRecoveryActionApplicationService recoveryActionService;
  private final SessionEvidenceApplicationService evidenceService;
  private final StateResyncAdmissionService stateResyncAdmissionService;
  private final BrowserStateSnapshotAssembler stateSnapshotAssembler;

  public NodeEventIngestionService(
      InboxEventJpaRepository inboxRepository,
      SessionCoordinator coordinator,
      BrowserStateRepository browserStateRepository,
      ProfileApplicationService profileApplicationService,
      StaticProxyApplicationService proxyApplicationService,
      SessionRepository sessionRepository,
      NodeCommandGateway nodeCommandGateway,
      AgentNavigationCompletionService agentNavigationCompletionService,
      AuditApplicationService auditService,
      DurableWorkflowApplicationService workflowService,
      BrowserCapacityApplicationService browserCapacityService,
      SessionResourceApplicationService resourceService,
      BusinessRecoveryActionApplicationService recoveryActionService,
      SessionEvidenceApplicationService evidenceService,
      StateResyncAdmissionService stateResyncAdmissionService,
      BrowserStateSnapshotAssembler stateSnapshotAssembler) {
    this.inboxRepository = inboxRepository;
    this.coordinator = coordinator;
    this.browserStateRepository = browserStateRepository;
    this.profileApplicationService = profileApplicationService;
    this.proxyApplicationService = proxyApplicationService;
    this.sessionRepository = sessionRepository;
    this.nodeCommandGateway = nodeCommandGateway;
    this.agentNavigationCompletionService = agentNavigationCompletionService;
    this.auditService = auditService;
    this.workflowService = workflowService;
    this.browserCapacityService = browserCapacityService;
    this.resourceService = resourceService;
    this.recoveryActionService = recoveryActionService;
    this.evidenceService = evidenceService;
    this.stateResyncAdmissionService = stateResyncAdmissionService;
    this.stateSnapshotAssembler = stateSnapshotAssembler;
  }

  @Transactional
  public Receipt receive(NodeEventReceived command) {
    if (inboxRepository.existsById(command.eventId())) {
      return new Receipt(true);
    }

    var result = coordinator.handle(command);
    if (result.status() == CoordinatorResult.Status.REJECTED) {
      throw new NodeEventRejectedException(result.reason());
    }
    switch (command.event()) {
      case NodeEvent.StateUpdated state -> {
        browserStateRepository.save(command.tenantId(), command.contextEpoch(), state);
        agentNavigationCompletionService.stateUpdated(command, state);
        recoveryActionService.stateUpdated(command, state);
      }
      case NodeEvent.StateSnapshotBegin ignored -> acceptStateSnapshot(command);
      case NodeEvent.StateSnapshotChunk ignored -> acceptStateSnapshot(command);
      case NodeEvent.StateSnapshotCommit ignored -> acceptStateSnapshot(command);
      case NodeEvent.StateDiff diff -> {
        if (!browserStateRepository.applyDiff(command.tenantId(), command.contextEpoch(), diff)) {
          browserStateRepository.invalidate(
              command.tenantId(),
              command.contextEpoch(),
              command.sessionId(),
              diff.stateVersion(),
              "BASE_VERSION_MISMATCH");
          requestAutomaticFullResync(command, "BASE_VERSION_MISMATCH", "document");
        }
      }
      case NodeEvent.DiffTruncated truncated -> {
        browserStateRepository.invalidate(
            command.tenantId(),
            command.contextEpoch(),
            command.sessionId(),
            truncated.currentStateVersion(),
            truncated.reason());
        requestAutomaticFullResync(command, truncated.reason(), truncated.affectedRoot());
      }
      case NodeEvent.AgentNavigationFailed failed ->
          agentNavigationCompletionService.navigationFailed(command, failed);
      case NodeEvent.AgentActionFailed failed ->
          agentNavigationCompletionService.actionFailed(command, failed);
      case NodeEvent.HumanTakeoverReady ready ->
          browserStateRepository.save(command.tenantId(), command.contextEpoch(), ready.state());
      case NodeEvent.HumanTakeoverEnded ended ->
          browserStateRepository.save(command.tenantId(), command.contextEpoch(), ended.state());
      case NodeEvent.RuntimeResourcesAdjusted adjusted ->
          resourceService.recordAdjustmentAcknowledged(command.tenantId(), adjusted);
      case NodeEvent.RuntimeCrashed crashed ->
          resourceService.protectRuntimeCrash(
              command.sessionId(),
              command.tenantId(),
              "OOM".equals(crashed.crashType()) ? "OOM" : "CRASH",
              "NODE_RUNTIME_EVENT");
      case NodeEvent.EvidenceCaptured captured ->
          evidenceService.record(command.tenantId(), command.eventId(), captured);
      default -> {}
    }
    if (command.event() instanceof NodeEvent.RuntimeStopped stopped
        && !stopped.checkpointId().isBlank()) {
      profileApplicationService.recordCheckpoint(command.tenantId(), stopped);
    }
    if (command.event() instanceof NodeEvent.RuntimeStarted started) {
      proxyApplicationService.recordBound(command.tenantId(), started);
      browserCapacityService.activate(command.sessionId());
    } else if (command.event() instanceof NodeEvent.RuntimeStopped) {
      proxyApplicationService.release(command.sessionId());
      browserCapacityService.release(command.sessionId());
    }
    if (command.event() instanceof NodeEvent.RuntimeStarted
        || command.event() instanceof NodeEvent.RuntimeStopped) {
      workflowService.completeCallback(
          command.tenantId(),
          command.sessionId(),
          command.coordinatorTerm(),
          command.contextEpoch(),
          command.operationEpoch(),
          command.eventId());
    }

    appendAudit(command);
    inboxRepository.save(new InboxEventEntity(command.eventId(), CONSUMER_ID, Instant.now()));
    return new Receipt(false);
  }

  public record Receipt(boolean duplicate) {}

  private void appendAudit(NodeEventReceived command) {
    if (command.event() instanceof NodeEvent.StateSnapshotChunk) {
      return;
    }
    var eventName = command.event().getClass().getSimpleName();
    var contextCommit =
        command.event() instanceof NodeEvent.RuntimeStarted
            || command.event() instanceof NodeEvent.RuntimeStopped
            || command.event() instanceof NodeEvent.RuntimeCrashed;
    var details = new LinkedHashMap<String, Object>();
    details.put("coordinatorTerm", command.coordinatorTerm());
    details.put("contextEpoch", command.contextEpoch());
    details.put("operationEpoch", command.operationEpoch());
    details.put("sequence", command.sequence());
    if (command.event() instanceof NodeEvent.StateDiff diff && !diff.snapshotKind().isBlank()) {
      details.put("snapshotKind", diff.snapshotKind());
      details.put("requestedRootRef", diff.requestedRootRef());
      details.put("baseStateVersion", diff.baseStateVersion());
      details.put("stateVersion", diff.stateVersion());
    }
    if (command.event() instanceof NodeEvent.StateSnapshotBegin begin) {
      details.put("snapshotId", begin.snapshotId());
      details.put("snapshotKind", begin.snapshotKind());
      details.put("stateVersion", begin.stateVersion());
      details.put("totalChunks", begin.totalChunks());
      details.put("totalBytes", begin.totalBytes());
    } else if (command.event() instanceof NodeEvent.StateSnapshotCommit commit) {
      details.put("snapshotId", commit.snapshotId());
      details.put("totalChunks", commit.totalChunks());
      details.put("totalBytes", commit.totalBytes());
    }
    auditService.append(
        new AuditApplicationService.AuditRecord(
            command.tenantId(),
            command.sessionId(),
            contextCommit ? "SESSION_CONTEXT_COMMIT" : "NODE_EVENT_COMMIT",
            "NODE",
            nodeActorId(command),
            "SESSION",
            command.sessionId(),
            eventName,
            "COMMITTED",
            Map.copyOf(details),
            command.eventId()));
  }

  private String nodeActorId(NodeEventReceived command) {
    if (command.event() instanceof NodeEvent.RuntimeStarted started) {
      return started.nodeId();
    }
    // EventEnvelope does not carry node_id for every event type; the authenticated stream is the
    // actor until the protocol adds it to the signed envelope.
    return "node-event-stream";
  }

  private void acceptStateSnapshot(NodeEventReceived command) {
    stateSnapshotAssembler
        .accept(command)
        .ifPresent(
            state -> {
              browserStateRepository.save(command.tenantId(), command.contextEpoch(), state);
              agentNavigationCompletionService.stateUpdated(command, state);
              recoveryActionService.stateUpdated(command, state);
            });
  }

  private void requestAutomaticFullResync(
      NodeEventReceived event, String reason, String affectedRoot) {
    var session = sessionRepository.requireForUpdate(event.sessionId());
    var command =
        NodeCommands.requestStateResync(
            session, "FULL", affectedRoot, "AUTO_" + reason, "state-event:" + event.eventId());
    var admission =
        stateResyncAdmissionService.tryAdmitAutomaticFull(
            event.tenantId(), event.sessionId(), command.messageId(), "AUTO_" + reason);
    if (admission.admitted()) {
      nodeCommandGateway.send(command);
      return;
    }
    auditService.append(
        new AuditApplicationService.AuditRecord(
            event.tenantId(),
            event.sessionId(),
            "STATE_RESYNC_CIRCUIT_OPEN",
            "NODE",
            "node-event-stream",
            "SESSION",
            event.sessionId(),
            "REQUEST_FULL_RESYNC",
            "BLOCKED",
            Map.of(
                "scope",
                admission.scope(),
                "retryAfterSeconds",
                admission.retryAfterSeconds(),
                "reason",
                reason,
                "affectedRoot",
                affectedRoot),
            event.eventId()));
  }

  public static final class NodeEventRejectedException extends RuntimeException {
    private final String reason;

    public NodeEventRejectedException(String reason) {
      super("Node event rejected: " + reason);
      this.reason = reason;
    }

    public String reason() {
      return reason;
    }
  }
}
