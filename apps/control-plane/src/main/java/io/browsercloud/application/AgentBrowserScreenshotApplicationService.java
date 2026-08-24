package io.browsercloud.application;

import static io.browsercloud.api.AgentBrowserScreenshotModels.*;
import static io.browsercloud.api.SessionEvidenceModels.EvidencePurpose.AGENT_PERCEPTION;

import io.browsercloud.api.AgentBrowserPerceptionModels;
import io.browsercloud.api.SessionEvidenceModels.RedeemEvidenceAccessResponse;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import io.browsercloud.domain.session.SessionState;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** State-fenced screenshot orchestration over the existing redacted Evidence data plane. */
@Service
public class AgentBrowserScreenshotApplicationService {
  private static final int MAX_WAITERS = 32;

  private final SessionRepository sessions;
  private final BrowserCapacityApplicationService capacity;
  private final AgentBrowserPerceptionService perception;
  private final AgentBrowserScreenshotStore store;
  private final NodeCommandGateway commands;
  private final SessionEvidenceGovernanceService evidence;
  private final AuditApplicationService audit;
  private final Semaphore waiters = new Semaphore(MAX_WAITERS);

  public AgentBrowserScreenshotApplicationService(
      SessionRepository sessions,
      BrowserCapacityApplicationService capacity,
      AgentBrowserPerceptionService perception,
      AgentBrowserScreenshotStore store,
      NodeCommandGateway commands,
      SessionEvidenceGovernanceService evidence,
      AuditApplicationService audit) {
    this.sessions = sessions;
    this.capacity = capacity;
    this.perception = perception;
    this.store = store;
    this.commands = commands;
    this.evidence = evidence;
    this.audit = audit;
  }

  @Transactional
  public ScreenshotView capture(
      String sessionId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      CaptureScreenshotRequest request) {
    var session = sessions.requireForUpdate(sessionId);
    requireTenant(session.tenantId(), tenantId, sessionId);
    if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
      throw new AgentBrowserScreenshotException("SESSION_NOT_RUNNING");
    }
    if (session.nodeId() == null
        || !capacity.nodeHasCapability(
            session.nodeId(), "agentScreenshot", "state-fenced-region-v1")) {
      throw new AgentBrowserScreenshotException("AGENT_SCREENSHOT_UNAVAILABLE");
    }
    var snapshot = perception.snapshot(sessionId, tenantId);
    if (!snapshot.stateCursor().equals(request.expectedStateCursor())) {
      throw new AgentBrowserScreenshotException("STATE_CURSOR_STALE");
    }
    if (snapshot.activeTab() == null || snapshot.activeTab().tabId().isBlank()) {
      throw new AgentBrowserScreenshotException("ACTIVE_TAB_UNAVAILABLE");
    }
    validateTarget(request, snapshot);
    var requestHash = requestHash(sessionId, request);
    var existing = store.findIdentityByIdempotency(tenantId, actorId, idempotencyKey);
    if (existing.isPresent()) {
      var identity = existing.orElseThrow();
      if (!identity.sessionId().equals(sessionId) || !identity.requestHash().equals(requestHash)) {
        throw new AgentBrowserScreenshotException("SCREENSHOT_IDEMPOTENCY_CONFLICT");
      }
      return store
          .find(tenantId, sessionId, identity.screenshotId(), actorId)
          .orElseThrow(() -> new AgentBrowserScreenshotException("SCREENSHOT_STATE_UNAVAILABLE"));
    }

    var cursor = parseCursor(request.expectedStateCursor());
    var now = Instant.now();
    var screenshotId = id("shot_", 20);
    var commandId = id("cmd_", 20);
    var grantId = id("egr_", 20);
    var plannedEvidenceId = "evd_" + UUID.randomUUID().toString().replace("-", "");
    var record =
        new AgentBrowserScreenshotStore.RequestRecord(
            screenshotId,
            tenantId,
            sessionId,
            actorId,
            idempotencyKey,
            requestHash,
            requestId,
            commandId,
            grantId,
            plannedEvidenceId,
            session.nodeId(),
            session.coordinatorTerm(),
            session.contextEpoch(),
            request.mode(),
            cursor.stateVersion(),
            cursor.targetRevision(),
            cursor.stateHash(),
            snapshot.activeTab().tabId(),
            request.mode() == ScreenshotMode.ELEMENT ? normalizedElementId(request) : null,
            request.region(),
            now);
    if (!store.insert(record)) {
      var raced =
          store
              .findIdentityByIdempotency(tenantId, actorId, idempotencyKey)
              .orElseThrow(
                  () -> new AgentBrowserScreenshotException("SCREENSHOT_IDEMPOTENCY_CONFLICT"));
      if (!raced.sessionId().equals(sessionId) || !raced.requestHash().equals(requestHash)) {
        throw new AgentBrowserScreenshotException("SCREENSHOT_IDEMPOTENCY_CONFLICT");
      }
      return store
          .find(tenantId, sessionId, raced.screenshotId(), actorId)
          .orElseThrow(() -> new AgentBrowserScreenshotException("SCREENSHOT_STATE_UNAVAILABLE"));
    }
    var region = request.region();
    commands.send(
        NodeCommands.captureAgentScreenshot(
            session,
            screenshotId,
            plannedEvidenceId,
            commandId,
            request.mode().name(),
            cursor.stateVersion(),
            cursor.targetRevision(),
            cursor.stateHash(),
            snapshot.activeTab().tabId(),
            normalizedElementId(request),
            region == null ? null : region.x(),
            region == null ? null : region.y(),
            region == null ? null : region.width(),
            region == null ? null : region.height(),
            now.toEpochMilli()));
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "AGENT_BROWSER_SCREENSHOT",
            "AGENT",
            actorId,
            "AGENT_BROWSER_SCREENSHOT",
            screenshotId,
            "CAPTURE",
            "ACCEPTED",
            auditMetadata(request),
            requestId));
    return store
        .find(tenantId, sessionId, screenshotId, actorId)
        .orElseThrow(() -> new AgentBrowserScreenshotException("SCREENSHOT_STATE_UNAVAILABLE"));
  }

  /** Bounded wait reads only PostgreSQL; it never polls Chromium or Object Storage. */
  public ScreenshotView get(
      String sessionId, String screenshotId, String tenantId, String actorId, int waitMs) {
    requireTenant(sessions.require(sessionId).tenantId(), tenantId, sessionId);
    if (waitMs > 0 && !waiters.tryAcquire()) {
      throw new AgentBrowserScreenshotException("SCREENSHOT_WAIT_CAPACITY_EXCEEDED");
    }
    try {
      var deadline = Instant.now().plus(Duration.ofMillis(waitMs));
      while (true) {
        var value =
            store
                .find(tenantId, sessionId, screenshotId, actorId)
                .orElseThrow(() -> new AgentBrowserScreenshotException("SCREENSHOT_NOT_FOUND"));
        if (!value.state().equals("EXECUTING") || waitMs == 0) return value;
        if (!Instant.now().isBefore(deadline)) {
          throw new AgentBrowserScreenshotException("SCREENSHOT_WAIT_TIMEOUT");
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new AgentBrowserScreenshotException("SCREENSHOT_WAIT_INTERRUPTED");
        }
      }
    } finally {
      if (waitMs > 0) waiters.release();
    }
  }

  public RedeemEvidenceAccessResponse redeem(
      String sessionId, String screenshotId, String tenantId, String actorId, String requestId) {
    requireTenant(sessions.require(sessionId).tenantId(), tenantId, sessionId);
    var claim =
        store
            .findRedeemClaim(tenantId, sessionId, screenshotId, actorId)
            .orElseThrow(() -> new AgentBrowserScreenshotException("SCREENSHOT_NOT_REDEEMABLE"));
    return evidence.redeem(
        sessionId, claim.accessGrantId(), tenantId, actorId, requestId, AGENT_PERCEPTION);
  }

  private static void validateTarget(
      CaptureScreenshotRequest request, AgentBrowserPerceptionModels.SnapshotView snapshot) {
    if (request.mode() != ScreenshotMode.ELEMENT) return;
    var elementId = normalizedElementId(request);
    var target =
        snapshot.state().targets().stream()
            .filter(
                candidate ->
                    candidate.elementId().equals(elementId)
                        || candidate.targetRef().equals(elementId))
            .findFirst()
            .orElseThrow(() -> new AgentBrowserScreenshotException("ELEMENT_NOT_FOUND"));
    if (!target.visible()) throw new AgentBrowserScreenshotException("ELEMENT_NOT_VISIBLE");
    if (!target.inViewport()) {
      throw new AgentBrowserScreenshotException("ELEMENT_OUTSIDE_VIEWPORT");
    }
    if (target.occluded()) throw new AgentBrowserScreenshotException("ELEMENT_OCCLUDED");
    if (target.bounds() == null || target.bounds().width() < 1 || target.bounds().height() < 1) {
      throw new AgentBrowserScreenshotException("ELEMENT_LAYOUT_UNAVAILABLE");
    }
  }

  private static String requestHash(String sessionId, CaptureScreenshotRequest request) {
    var region = request.region();
    return PromptSecurityService.sha256(
        String.join(
            "|",
            sessionId,
            request.mode().name(),
            request.expectedStateCursor(),
            normalizedElementId(request),
            region == null ? "" : Double.toHexString(region.x()),
            region == null ? "" : Double.toHexString(region.y()),
            region == null ? "" : Double.toHexString(region.width()),
            region == null ? "" : Double.toHexString(region.height())));
  }

  private static Map<String, Object> auditMetadata(CaptureScreenshotRequest request) {
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("mode", request.mode().name());
    metadata.put("stateCursorHash", PromptSecurityService.sha256(request.expectedStateCursor()));
    if (!normalizedElementId(request).isBlank()) {
      metadata.put("elementId", normalizedElementId(request));
    }
    if (request.region() != null) {
      metadata.put("regionWidth", request.region().width());
      metadata.put("regionHeight", request.region().height());
    }
    return Map.copyOf(metadata);
  }

  private static String normalizedElementId(CaptureScreenshotRequest request) {
    return request.elementId() == null ? "" : request.elementId().strip();
  }

  private static StateCursor parseCursor(String value) {
    var parts = value.split(":", 3);
    try {
      var stateVersion = Long.parseLong(parts[0]);
      var targetRevision = Long.parseLong(parts[1]);
      if (parts.length != 3 || stateVersion < 1 || targetRevision < 1)
        throw new NumberFormatException();
      return new StateCursor(stateVersion, targetRevision, parts[2]);
    } catch (RuntimeException exception) {
      throw new AgentBrowserScreenshotException("STATE_CURSOR_INVALID");
    }
  }

  private static void requireTenant(
      String authoritativeTenantId, String tenantId, String sessionId) {
    if (!authoritativeTenantId.equals(tenantId)) throw new SessionNotFoundException(sessionId);
  }

  private static String id(String prefix, int length) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, length);
  }

  private record StateCursor(long stateVersion, long targetRevision, String stateHash) {}

  public static final class AgentBrowserScreenshotException extends RuntimeException {
    public AgentBrowserScreenshotException(String code) {
      super(code);
    }
  }
}
