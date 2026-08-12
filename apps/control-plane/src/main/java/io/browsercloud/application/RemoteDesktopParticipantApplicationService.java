package io.browsercloud.application;

import static io.browsercloud.api.RemoteDesktopParticipantModels.*;

import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.RemoteDesktopParticipantEntity;
import io.browsercloud.persistence.RemoteDesktopParticipantJpaRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RemoteDesktopParticipantApplicationService {
  private static final List<String> ONLINE_STATES = List.of("CONNECTED", "REVOKE_REQUESTED");

  private final RemoteDesktopParticipantJpaRepository participants;
  private final SessionRepository sessions;
  private final NodeCommandGateway nodeCommands;
  private final AuditApplicationService audit;
  private final RemoteDesktopUsageCostAttributionService usageCosts;

  public RemoteDesktopParticipantApplicationService(
      RemoteDesktopParticipantJpaRepository participants,
      SessionRepository sessions,
      NodeCommandGateway nodeCommands,
      AuditApplicationService audit,
      RemoteDesktopUsageCostAttributionService usageCosts) {
    this.participants = participants;
    this.sessions = sessions;
    this.nodeCommands = nodeCommands;
    this.audit = audit;
    this.usageCosts = usageCosts;
  }

  @Transactional(readOnly = true)
  public RemoteDesktopParticipantListResponse list(String sessionId, String tenantId) {
    var session = sessions.require(sessionId);
    requireTenant(session, tenantId);
    var items =
        participants
            .findAllByTenantIdAndSessionIdAndContextEpochAndStateInOrderByObservedAtDescConnectionId(
                tenantId, sessionId, session.contextEpoch(), ONLINE_STATES)
            .stream()
            .map(RemoteDesktopParticipantApplicationService::view)
            .toList();
    return new RemoteDesktopParticipantListResponse(items, items.size());
  }

  @Transactional
  public RemoteDesktopParticipantView revoke(
      String sessionId,
      String connectionId,
      String tenantId,
      String actorId,
      String requestId,
      String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
      throw new RemoteDesktopParticipantRejectedException("INVALID_IDEMPOTENCY_KEY");
    }
    var session = sessions.requireForUpdate(sessionId);
    requireTenant(session, tenantId);
    if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
      throw new RemoteDesktopParticipantRejectedException("SESSION_NOT_RUNNING");
    }
    var participant =
        participants
            .findForUpdate(connectionId, tenantId, sessionId)
            .orElseThrow(RemoteDesktopParticipantNotFoundException::new);
    if ("REVOKE_REQUESTED".equals(participant.getState())
        || "REVOKED".equals(participant.getState())
        || "DISCONNECTED".equals(participant.getState())) {
      return view(participant);
    }
    var now = Instant.now();
    participant.requestRevoke(actorId, now);
    participants.save(participant);
    nodeCommands.send(
        NodeCommands.revokeRemoteDesktopConnection(
            session, connectionId, "ADMIN_REQUESTED", actorId, idempotencyKey));
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "REMOTE_DESKTOP_GOVERNANCE",
            "HUMAN",
            actorId,
            "REMOTE_DESKTOP_CONNECTION",
            connectionId,
            "REVOKE_CONNECTION",
            "ACCEPTED",
            Map.of("connectionId", connectionId, "agentPreserved", true),
            requestId));
    return view(participant);
  }

  @Transactional
  public void record(NodeEventReceived envelope, NodeEvent.RemoteDesktopParticipantChanged event) {
    var observedAt = Instant.ofEpochMilli(event.observedAtMs());
    var participant =
        participants
            .findByIdForUpdate(event.connectionId())
            .orElseGet(
                () ->
                    new RemoteDesktopParticipantEntity(
                        event.connectionId(),
                        envelope.tenantId(),
                        envelope.sessionId(),
                        envelope.contextEpoch(),
                        observedAt));
    if (!participant.getTenantId().equals(envelope.tenantId())
        || !participant.getSessionId().equals(envelope.sessionId())) {
      throw new RemoteDesktopParticipantRejectedException("PARTICIPANT_SCOPE_MISMATCH");
    }
    participant.apply(
        envelope.contextEpoch(),
        event.actorId(),
        event.accessMode(),
        event.viewOnly(),
        event.state(),
        event.reason(),
        event.revokedBy(),
        observedAt);
    var previousBytes = participant.getForwardedBytes();
    long billableBytes = Math.max(0, event.forwardedBytes() - previousBytes);
    var rate =
        billableBytes == 0
            ? null
            : usageCosts
                .resolve(envelope.tenantId(), envelope.sessionId(), observedAt)
                .orElse(null);
    var attributedCost = rate == null ? null : usageCosts.price(billableBytes, rate);
    var delta =
        participant.applyUsage(
            event.forwardedBytes(),
            event.quotaWaitMillis(),
            event.throttledBatches(),
            attributedCost,
            rate == null ? null : rate.pricingVersion(),
            rate == null ? null : rate.egressGibUsd());
    participants.save(participant);
    if (delta.forwardedBytes() > 0 || delta.quotaWaitMillis() > 0 || delta.throttledBatches() > 0) {
      usageCosts.appendLedger(
          envelope.eventId(),
          participant.getConnectionId(),
          envelope.tenantId(),
          envelope.sessionId(),
          participant.getActorId(),
          delta.forwardedBytes(),
          delta.quotaWaitMillis(),
          delta.throttledBatches(),
          rate,
          attributedCost == null ? BigDecimal.ZERO.setScale(9) : attributedCost,
          observedAt);
    }
  }

  private void requireTenant(String sessionId, String tenantId) {
    requireTenant(sessions.require(sessionId), tenantId);
  }

  private void requireTenant(
      io.browsercloud.domain.session.SessionContext session, String tenantId) {
    if (!tenantId.equals(session.tenantId())) {
      throw new TenantAccessDeniedException(session.sessionId());
    }
  }

  private static RemoteDesktopParticipantView view(RemoteDesktopParticipantEntity value) {
    return new RemoteDesktopParticipantView(
        value.getConnectionId(),
        value.getSessionId(),
        value.getContextEpoch(),
        value.getActorId(),
        value.getAccessMode(),
        value.getViewOnly(),
        value.getState(),
        value.getReason(),
        value.getConnectedAt(),
        value.getDisconnectedAt(),
        value.getRevokedBy(),
        value.getRevokeRequestedAt(),
        value.getObservedAt(),
        value.getUpdatedAt(),
        value.getForwardedBytes(),
        value.getQuotaWaitMillis(),
        value.getThrottledBatches(),
        value.getEgressCostUsd(),
        value.getUnpricedForwardedBytes(),
        value.getLastCostPricingVersion(),
        value.getLastEgressGibUsd());
  }

  public static final class RemoteDesktopParticipantNotFoundException extends RuntimeException {
    public RemoteDesktopParticipantNotFoundException() {
      super("remote desktop participant not found");
    }
  }

  public static final class RemoteDesktopParticipantRejectedException extends RuntimeException {
    public RemoteDesktopParticipantRejectedException(String message) {
      super(message);
    }
  }
}
