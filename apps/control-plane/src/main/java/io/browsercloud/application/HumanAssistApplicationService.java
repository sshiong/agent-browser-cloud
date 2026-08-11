package io.browsercloud.application;

import static io.browsercloud.api.ChallengeModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.OperationFactory;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.ChallengeEventEntity;
import io.browsercloud.persistence.ChallengeEventJpaRepository;
import io.browsercloud.persistence.HumanClickIntentEntity;
import io.browsercloud.persistence.HumanClickIntentJpaRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User authorization and single-use execution boundary for Human Assist. */
@Service
public class HumanAssistApplicationService {

  private final ChallengeEventJpaRepository events;
  private final HumanClickIntentJpaRepository intents;
  private final BrowserStateRepository browserStates;
  private final SessionRepository sessions;
  private final OperationRepository operations;
  private final NodeCommandGateway nodeCommands;
  private final AuditApplicationService audit;
  private final AgentExecutionService agentExecution;
  private final ObjectMapper objectMapper;

  public HumanAssistApplicationService(
      ChallengeEventJpaRepository events,
      HumanClickIntentJpaRepository intents,
      BrowserStateRepository browserStates,
      SessionRepository sessions,
      OperationRepository operations,
      NodeCommandGateway nodeCommands,
      AuditApplicationService audit,
      AgentExecutionService agentExecution,
      ObjectMapper objectMapper) {
    this.events = events;
    this.intents = intents;
    this.browserStates = browserStates;
    this.sessions = sessions;
    this.operations = operations;
    this.nodeCommands = nodeCommands;
    this.audit = audit;
    this.agentExecution = agentExecution;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public ChallengeEventListResponse list(String sessionId, String tenantId, int limit) {
    requireTenant(sessionId, tenantId);
    return new ChallengeEventListResponse(
        events
            .findAllByTenantIdAndSessionIdOrderByDetectedAtDescChallengeEventIdDesc(
                tenantId, sessionId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
            .stream()
            .map(this::view)
            .toList());
  }

  @Transactional(readOnly = true)
  public ChallengeEventView get(String eventId, String tenantId) {
    return view(requireEvent(eventId, tenantId));
  }

  @Transactional(readOnly = true)
  public ChallengePreviewView preview(String eventId, String tenantId, String userId) {
    var event = requireEvent(eventId, tenantId);
    return preview(event, userId, Instant.now());
  }

  @Transactional
  public HumanAssistView authorize(
      String eventId,
      String tenantId,
      String userId,
      String idempotencyKey,
      String requestId,
      AuthorizeHumanAssistRequest request) {
    if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
      throw new HumanAssistRejectedException("INVALID_IDEMPOTENCY_KEY");
    }
    var existing = intents.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
    if (existing.isPresent()) {
      var intent = existing.orElseThrow();
      if (!intent.getChallengeEventId().equals(eventId) || !intent.getUserId().equals(userId)) {
        throw new HumanAssistRejectedException("IDEMPOTENCY_KEY_REUSED");
      }
      return view(intent);
    }
    var now = Instant.now();
    var event =
        events.findForUpdate(eventId, tenantId).orElseThrow(ChallengeEventNotFoundException::new);
    if (!"CONFIRMED".equals(event.getStatus())
        || !"SINGLE_CLICK".equals(event.getSuspectedType())) {
      throw new HumanAssistRejectedException("CHALLENGE_REQUIRES_TAKEOVER");
    }
    var preview = preview(event, userId, now);
    if (!preview.canAuthorize()) {
      throw new HumanAssistRejectedException(preview.blockingReason());
    }
    if (!preview.previewHash().equals(request.previewHash())
        || event.getStateVersion() != request.expectedStateVersion()
        || event.getTargetRevision() != request.expectedTargetRevision()) {
      throw new HumanAssistRejectedException("STALE_CHALLENGE_PREVIEW");
    }
    var session = sessions.requireForUpdate(event.getSessionId());
    if (!tenantId.equals(session.tenantId())) {
      throw new TenantAccessDeniedException(event.getSessionId());
    }
    if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
      throw new HumanAssistRejectedException("SESSION_NOT_RUNNING");
    }
    operations.ensureNoActiveOperation(session.sessionId());
    var operation =
        OperationFactory.humanAssist(
            session, userId, operations.nextOperationEpoch(session.sessionId()));
    var authorization =
        audit.append(
            new AuditApplicationService.AuditRecord(
                tenantId,
                session.sessionId(),
                "HUMAN_ASSIST_AUTHORIZATION",
                "HUMAN",
                userId,
                "CHALLENGE_EVENT",
                eventId,
                "AUTHORIZE_ONE_CLICK",
                "APPROVED",
                Map.of(
                    "previewHash",
                    preview.previewHash(),
                    "stateVersion",
                    event.getStateVersion(),
                    "targetRevision",
                    event.getTargetRevision(),
                    "allowedActionCount",
                    1,
                    "automaticRetry",
                    false),
                requestId));
    var bounds = preview.highlight();
    var intent =
        new HumanClickIntentEntity(
            id("hint_"),
            eventId,
            tenantId,
            userId,
            session.sessionId(),
            session.contextEpoch(),
            event.getStateVersion(),
            event.getTargetRevision(),
            json(bounds),
            event.getTargetRef(),
            event.getVisualAnchorHash(),
            authorization.eventId(),
            requestId,
            idempotencyKey,
            min(event.getExpiresAt(), now.plusSeconds(30)),
            now);
    operations.insert(operation);
    intent.consume(operation.operationId(), now);
    intents.save(intent);
    event.authorize(now);
    event.executing(now);
    events.save(event);
    var state = requireCurrentState(event, tenantId);
    var target = requireCurrentTarget(event, state.state());
    nodeCommands.send(
        NodeCommands.humanAssistClick(
            session,
            operation,
            eventId,
            intent.getIntentId(),
            event.getTargetRef(),
            event.getTargetRevision(),
            event.getStateVersion(),
            state.state().stateHash(),
            target.bounds(),
            event.getVisualAnchorHash()));
    return view(intent);
  }

  /** Commits a successful Human Assist state. Agent continuation is decided after re-detection. */
  @Transactional
  public StateCommit stateUpdated(NodeEventReceived envelope, NodeEvent.StateUpdated state) {
    if (!"HUMAN_ASSIST".equals(state.snapshotKind()) || envelope.operationEpoch() == 0) {
      return StateCommit.notHumanAssist();
    }
    var operation =
        operations
            .findActive(envelope.sessionId())
            .filter(value -> value.operationEpoch() == envelope.operationEpoch())
            .filter(value -> value.mode() == OperationMode.HUMAN_ASSIST)
            .orElseThrow(() -> new HumanAssistRejectedException("STALE_HUMAN_ASSIST_OPERATION"));
    var intent =
        intents
            .findByOperationIdForUpdate(operation.operationId())
            .orElseThrow(() -> new HumanAssistRejectedException("HUMAN_ASSIST_INTENT_NOT_FOUND"));
    var event =
        events
            .findForUpdate(intent.getChallengeEventId(), envelope.tenantId())
            .orElseThrow(ChallengeEventNotFoundException::new);
    if (!intent.getIntentId().equals(state.requestedRootRef())
        || !intent.getSessionId().equals(state.sessionId())
        || state.stateVersion() <= intent.getStateVersion()
        || !java.util.Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())) {
      fail(intent, event, operation.operationId(), "POST_ASSIST_STATE_INVALID", envelope.eventId());
      return StateCommit.failed();
    }
    var now = Instant.now();
    intent.committed(now);
    event.resolved(now);
    intents.save(intent);
    events.save(event);
    operations.transitionPhase(
        operation.operationId(), OperationPhase.EXECUTING, OperationPhase.COMPLETING);
    operations.transition(operation.operationId(), OperationState.ACTIVE, OperationState.COMMITTED);
    audit.append(
        new AuditApplicationService.AuditRecord(
            envelope.tenantId(),
            envelope.sessionId(),
            "HUMAN_ASSIST_COMPLETED",
            "NODE",
            "browser-node",
            "HUMAN_CLICK_INTENT",
            intent.getIntentId(),
            "EXECUTE_ONE_CLICK",
            "COMMITTED",
            Map.of(
                "challengeEventId",
                event.getChallengeEventId(),
                "stateVersion",
                state.stateVersion(),
                "consumedCount",
                intent.getConsumedCount()),
            envelope.eventId()));
    return StateCommit.committed(event.getChallengeEventId());
  }

  /** Resumes only when the authoritative post-click State contains no new Challenge. */
  @Transactional
  public void continueAgentAfterState(
      String completedChallengeEventId, String nextChallengeEventId, String tenantId) {
    if (nextChallengeEventId == null) {
      agentExecution.resumeAfterHumanAssist(completedChallengeEventId, tenantId);
    }
  }

  /**
   * Resolves a takeover-only Challenge and resumes its Agent only when no new Challenge remains.
   */
  @Transactional
  public void humanTakeoverEnded(
      NodeEventReceived envelope, NodeEvent.HumanTakeoverEnded ended, String nextChallengeEventId) {
    var completedEventId =
        agentExecution.continueAfterHumanTakeover(
            envelope.sessionId(), envelope.tenantId(), nextChallengeEventId);
    if (completedEventId == null
        || completedEventId.equals(nextChallengeEventId)
        || nextChallengeEventId != null) return;
    var event = events.findForUpdate(completedEventId, envelope.tenantId()).orElse(null);
    if (event == null
        || java.util.Set.of("RESOLVED", "FAILED", "EXPIRED").contains(event.getStatus())) return;
    var now = Instant.now();
    event.resolvedByHumanTakeover(now);
    events.save(event);
    audit.append(
        new AuditApplicationService.AuditRecord(
            envelope.tenantId(),
            envelope.sessionId(),
            "CHALLENGE_RESOLVED_BY_HUMAN_TAKEOVER",
            "HUMAN",
            ended.userId(),
            "CHALLENGE_EVENT",
            completedEventId,
            "RESOLVE",
            "COMMITTED",
            Map.of("stateVersion", ended.state().stateVersion(), "reason", ended.reason()),
            envelope.eventId()));
  }

  public record StateCommit(boolean humanAssist, boolean committed, String challengeEventId) {
    static StateCommit notHumanAssist() {
      return new StateCommit(false, false, null);
    }

    static StateCommit failed() {
      return new StateCommit(true, false, null);
    }

    static StateCommit committed(String challengeEventId) {
      return new StateCommit(true, true, challengeEventId);
    }
  }

  @Transactional
  public void failed(NodeEventReceived envelope, NodeEvent.HumanAssistFailed failure) {
    var operation =
        operations
            .findActive(envelope.sessionId())
            .filter(value -> value.operationEpoch() == envelope.operationEpoch())
            .filter(value -> value.mode() == OperationMode.HUMAN_ASSIST)
            .orElseThrow(() -> new HumanAssistRejectedException("STALE_HUMAN_ASSIST_OPERATION"));
    var intent =
        intents
            .findByOperationIdForUpdate(operation.operationId())
            .filter(value -> value.getIntentId().equals(failure.intentId()))
            .filter(value -> value.getChallengeEventId().equals(failure.challengeEventId()))
            .orElseThrow(() -> new HumanAssistRejectedException("STALE_HUMAN_ASSIST_INTENT"));
    var event =
        events
            .findForUpdate(failure.challengeEventId(), envelope.tenantId())
            .orElseThrow(ChallengeEventNotFoundException::new);
    fail(intent, event, operation.operationId(), safeCode(failure.errorCode()), envelope.eventId());
  }

  @Scheduled(fixedDelayString = "${challenge.expiry-scan-interval-ms:15000}")
  @Transactional
  public void expire() {
    var now = Instant.now();
    for (var intent : intents.findExpiredForUpdate(now, PageRequest.of(0, 100))) {
      var event =
          events.findForUpdate(intent.getChallengeEventId(), intent.getTenantId()).orElse(null);
      if ("AUTHORIZED".equals(intent.getState())) {
        intent.expire(now);
        if (event != null) event.expire(now);
      } else if ("EXECUTING".equals(intent.getState())) {
        intent.failed("HUMAN_ASSIST_EXPIRED", now);
        if (event != null) event.failed(now);
        operations
            .findActive(intent.getSessionId())
            .filter(value -> value.operationId().equals(intent.getOperationId()))
            .ifPresent(
                value ->
                    operations.transition(
                        value.operationId(), OperationState.ACTIVE, OperationState.TIMED_OUT));
      }
      intents.save(intent);
      if (event != null) events.save(event);
    }
    for (var event : events.findExpiredForUpdate(now, PageRequest.of(0, 100))) {
      event.expire(now);
      events.save(event);
    }
  }

  private ChallengePreviewView preview(ChallengeEventEntity event, String userId, Instant now) {
    var state = browserStates.find(event.getSessionId()).orElse(null);
    var target =
        state == null
            ? null
            : state.state().targets().stream()
                .filter(value -> value.targetRef().equals(event.getTargetRef()))
                .findFirst()
                .orElse(null);
    var blocking = blockingReason(event, state, target, now);
    var region = target == null || target.bounds() == null ? null : region(target.bounds());
    var previewHash =
        blocking == null
            ? PromptSecurityService.sha256(
                String.join(
                    "\n",
                    event.getChallengeEventId(),
                    userId,
                    Long.toString(event.getContextEpoch()),
                    Long.toString(event.getStateVersion()),
                    Long.toString(event.getTargetRevision()),
                    event.getTargetRef(),
                    event.getVisualAnchorHash()))
            : "";
    return new ChallengePreviewView(
        view(event),
        region == null ? "" : previewHash,
        region,
        blocking == null,
        blocking == null,
        blocking,
        now);
  }

  private String blockingReason(
      ChallengeEventEntity event,
      BrowserStateRepository.Snapshot snapshot,
      NodeEvent.InteractiveTarget target,
      Instant now) {
    if (!"CONFIRMED".equals(event.getStatus()) || !"SINGLE_CLICK".equals(event.getSuspectedType()))
      return "CHALLENGE_REQUIRES_TAKEOVER";
    if (!event.getAuthorizationDeadline().isAfter(now) || !event.getExpiresAt().isAfter(now)) {
      return "CHALLENGE_AUTHORIZATION_EXPIRED";
    }
    if (snapshot == null
        || !snapshot.tenantId().equals(event.getTenantId())
        || snapshot.contextEpoch() != event.getContextEpoch()) return "STALE_CHALLENGE_CONTEXT";
    var state = snapshot.state();
    if (state.stateVersion() != event.getStateVersion()
        || state.targetRevision() != event.getTargetRevision()
        || !java.util.Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())) {
      return "STALE_CHALLENGE_STATE";
    }
    if (target == null
        || target.bounds() == null
        || !target.visible()
        || !target.enabled()
        || target.sensitive()) return "CHALLENGE_TARGET_UNAVAILABLE";
    if (!event
        .getVisualAnchorHash()
        .equals(ChallengeDetectionService.visualAnchor(state, target))) {
      return "VISUAL_ANCHOR_MISMATCH";
    }
    var active = operations.findActive(event.getSessionId());
    if (active.isPresent()) {
      return active.orElseThrow().mode() == OperationMode.HUMAN_TAKEOVER
          ? "HUMAN_TAKEOVER_ACTIVE"
          : "ACTIVE_OPERATION_EXISTS";
    }
    return null;
  }

  private BrowserStateRepository.Snapshot requireCurrentState(
      ChallengeEventEntity event, String tenantId) {
    var state =
        browserStates
            .find(event.getSessionId())
            .orElseThrow(() -> new HumanAssistRejectedException("CURRENT_STATE_UNAVAILABLE"));
    if (!tenantId.equals(state.tenantId())
        || state.contextEpoch() != event.getContextEpoch()
        || state.state().stateVersion() != event.getStateVersion()
        || state.state().targetRevision() != event.getTargetRevision()) {
      throw new HumanAssistRejectedException("STALE_CHALLENGE_STATE");
    }
    return state;
  }

  private NodeEvent.InteractiveTarget requireCurrentTarget(
      ChallengeEventEntity event, NodeEvent.StateUpdated state) {
    return state.targets().stream()
        .filter(value -> value.targetRef().equals(event.getTargetRef()))
        .filter(
            value ->
                event
                    .getVisualAnchorHash()
                    .equals(ChallengeDetectionService.visualAnchor(state, value)))
        .findFirst()
        .orElseThrow(() -> new HumanAssistRejectedException("VISUAL_ANCHOR_MISMATCH"));
  }

  private void fail(
      HumanClickIntentEntity intent,
      ChallengeEventEntity event,
      String operationId,
      String errorCode,
      String requestId) {
    var now = Instant.now();
    intent.failed(errorCode, now);
    event.failed(now);
    intents.save(intent);
    events.save(event);
    operations.transition(operationId, OperationState.ACTIVE, OperationState.ABORTED);
    audit.append(
        new AuditApplicationService.AuditRecord(
            intent.getTenantId(),
            intent.getSessionId(),
            "HUMAN_ASSIST_FAILED",
            "NODE",
            "browser-node",
            "HUMAN_CLICK_INTENT",
            intent.getIntentId(),
            "EXECUTE_ONE_CLICK",
            "FAILED",
            Map.of(
                "challengeEventId",
                event.getChallengeEventId(),
                "errorCode",
                errorCode,
                "automaticRetry",
                false),
            requestId));
  }

  private ChallengeEventEntity requireEvent(String eventId, String tenantId) {
    return events
        .findById(eventId)
        .filter(value -> value.getTenantId().equals(tenantId))
        .orElseThrow(ChallengeEventNotFoundException::new);
  }

  private void requireTenant(String sessionId, String tenantId) {
    if (!tenantId.equals(sessions.require(sessionId).tenantId())) {
      throw new TenantAccessDeniedException(sessionId);
    }
  }

  private ChallengeEventView view(ChallengeEventEntity event) {
    return new ChallengeEventView(
        event.getChallengeEventId(),
        event.getSessionId(),
        event.getContextEpoch(),
        event.getStateVersion(),
        event.getTargetRevision(),
        event.getConfidence(),
        readMap(event.getEvidence()),
        event.getSuspectedType(),
        event.getAccessOutcome(),
        event.getTargetRef(),
        event.getTargetSummary(),
        event.getStatus(),
        "SINGLE_CLICK".equals(event.getSuspectedType()),
        event.getDetectedAt(),
        event.getAuthorizationDeadline(),
        event.getExpiresAt(),
        event.getUpdatedAt());
  }

  private HumanAssistView view(HumanClickIntentEntity intent) {
    return new HumanAssistView(
        intent.getIntentId(),
        intent.getChallengeEventId(),
        intent.getSessionId(),
        intent.getUserId(),
        intent.getContextEpoch(),
        intent.getStateVersion(),
        intent.getTargetRevision(),
        intent.getAllowedTargetRef(),
        intent.getAllowedActionCount(),
        intent.getConsumedCount(),
        intent.getAuthorizationEventId(),
        intent.getOperationId(),
        intent.getRequestId(),
        intent.getState(),
        intent.getExpiresAt(),
        intent.getCreatedAt(),
        intent.getConsumedAt(),
        intent.getCompletedAt(),
        intent.getErrorCode());
  }

  private ChallengeRegion region(NodeEvent.Bounds bounds) {
    return new ChallengeRegion(bounds.x(), bounds.y(), bounds.width(), bounds.height());
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Human Assist region is not serializable", exception);
    }
  }

  private Map<String, Object> readMap(String value) {
    try {
      return objectMapper.readValue(value, new TypeReference<>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Challenge evidence is invalid", exception);
    }
  }

  private static Instant min(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private static String safeCode(String value) {
    return value != null && value.matches("^[A-Z][A-Z0-9_]{2,127}$")
        ? value
        : "HUMAN_ASSIST_FAILED";
  }

  private static String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  public static final class ChallengeEventNotFoundException extends RuntimeException {}

  public static final class HumanAssistRejectedException extends RuntimeException {
    public HumanAssistRejectedException(String reason) {
      super(reason);
    }
  }
}
