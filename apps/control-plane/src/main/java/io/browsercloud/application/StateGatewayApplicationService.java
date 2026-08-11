package io.browsercloud.application;

import io.browsercloud.api.StateResyncRequest;
import io.browsercloud.api.StateResyncResponse;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.session.SessionState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Current State Diff/Resync 的控制面入口。 */
@Service
public class StateGatewayApplicationService {

  private final SessionRepository sessionRepository;
  private final BrowserStateRepository stateRepository;
  private final NodeCommandGateway nodeCommandGateway;
  private final IdempotencyService idempotencyService;
  private final StateResyncAdmissionService admissionService;
  private final AuditApplicationService auditService;

  public StateGatewayApplicationService(
      SessionRepository sessionRepository,
      BrowserStateRepository stateRepository,
      NodeCommandGateway nodeCommandGateway,
      IdempotencyService idempotencyService,
      StateResyncAdmissionService admissionService,
      AuditApplicationService auditService) {
    this.sessionRepository = sessionRepository;
    this.stateRepository = stateRepository;
    this.nodeCommandGateway = nodeCommandGateway;
    this.idempotencyService = idempotencyService;
    this.admissionService = admissionService;
    this.auditService = auditService;
  }

  @Transactional
  public StateResyncResponse requestResync(
      String sessionId,
      String tenantId,
      String actorId,
      StateResyncRequest request,
      String idempotencyKey) {
    return requestResync(sessionId, tenantId, "USER", actorId, false, request, idempotencyKey);
  }

  /** Workflow-owned Resync entry point; automatic Full requests share the loop circuit. */
  @Transactional
  public StateResyncResponse requestResync(
      String sessionId, String tenantId, StateResyncRequest request, String idempotencyKey) {
    return requestResync(
        sessionId, tenantId, "SYSTEM", "state-resync-workflow", true, request, idempotencyKey);
  }

  private StateResyncResponse requestResync(
      String sessionId,
      String tenantId,
      String actorType,
      String actorId,
      boolean automatic,
      StateResyncRequest request,
      String idempotencyKey) {
    var session = sessionRepository.requireForUpdate(sessionId);
    if (!session.tenantId().equals(tenantId)) {
      throw new TenantAccessDeniedException(sessionId);
    }
    if (session.state() != SessionState.RUNNING) {
      throw new InvalidStateResyncRequestException("State Resync requires a running Session");
    }
    var rootRef = request.rootRef() == null ? "" : request.rootRef().trim();
    if (request.mode() == StateResyncRequest.Mode.REGION && rootRef.isEmpty()) {
      throw new InvalidStateResyncRequestException("REGION State Resync requires rootRef");
    }
    if (rootRef.chars().anyMatch(Character::isISOControl)) {
      throw new InvalidStateResyncRequestException("State Resync rootRef contains control text");
    }
    var reason =
        request.reason() == null || request.reason().isBlank()
            ? "USER_REQUEST"
            : request.reason().trim();
    var command =
        NodeCommands.requestStateResync(
            session, request.mode().name(), rootRef, reason, idempotencyKey);
    var claimedRequestId =
        idempotencyService.claimStateResync(
            tenantId, sessionId, idempotencyKey, request, command.messageId());
    if (!claimedRequestId.equals(command.messageId())) {
      return new StateResyncResponse(claimedRequestId, request.mode().name(), "QUEUED");
    }
    if (automatic) {
      if (request.mode() != StateResyncRequest.Mode.FULL) {
        throw new InvalidStateResyncRequestException(
            "Workflow-owned State Resync must use FULL mode");
      }
      admissionService.admitAutomatic(tenantId, sessionId, actorId, command.messageId(), reason);
    } else {
      admissionService.admitUser(
          tenantId, sessionId, actorId, command.messageId(), request.mode(), rootRef, reason);
    }
    stateRepository.markResyncing(tenantId, session.contextEpoch(), sessionId);
    nodeCommandGateway.send(command);
    auditService.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "STATE_RESYNC_REQUESTED",
            actorType,
            actorId,
            "SESSION",
            sessionId,
            "REQUEST_" + request.mode().name() + "_RESYNC",
            "QUEUED",
            java.util.Map.of("mode", request.mode().name(), "reason", reason),
            command.messageId()));
    return new StateResyncResponse(command.messageId(), request.mode().name(), "QUEUED");
  }

  public static final class InvalidStateResyncRequestException extends RuntimeException {
    public InvalidStateResyncRequestException(String message) {
      super(message);
    }
  }
}
