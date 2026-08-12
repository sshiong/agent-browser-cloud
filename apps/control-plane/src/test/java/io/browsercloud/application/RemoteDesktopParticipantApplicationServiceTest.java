package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.RemoteDesktopParticipantEntity;
import io.browsercloud.persistence.RemoteDesktopParticipantJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RemoteDesktopParticipantApplicationServiceTest {
  private final RemoteDesktopParticipantJpaRepository participants =
      Mockito.mock(RemoteDesktopParticipantJpaRepository.class);
  private final SessionRepository sessions = Mockito.mock(SessionRepository.class);
  private final NodeCommandGateway nodeCommands = Mockito.mock(NodeCommandGateway.class);
  private final AuditApplicationService audit = Mockito.mock(AuditApplicationService.class);
  private final RemoteDesktopParticipantApplicationService service =
      new RemoteDesktopParticipantApplicationService(participants, sessions, nodeCommands, audit);

  @Test
  void shouldListOnlyCurrentEpochOnlineParticipants() {
    var session = runningSession();
    var participant = participant("rdc_1234567890abcdefghij");
    when(sessions.require(session.sessionId())).thenReturn(session);
    when(participants
            .findAllByTenantIdAndSessionIdAndContextEpochAndStateInOrderByObservedAtDescConnectionId(
                session.tenantId(),
                session.sessionId(),
                session.contextEpoch(),
                List.of("CONNECTED", "REVOKE_REQUESTED")))
        .thenReturn(List.of(participant));

    var result = service.list(session.sessionId(), session.tenantId());

    assertThat(result.onlineCount()).isEqualTo(1);
    assertThat(result.items())
        .extracting(item -> item.connectionId())
        .containsExactly(participant.getConnectionId());
  }

  @Test
  void shouldRevokeOneConnectionWithoutChangingSessionOrAgentOperation() {
    var session = runningSession();
    var participant = participant("rdc_1234567890abcdefghij");
    when(sessions.requireForUpdate(session.sessionId())).thenReturn(session);
    when(participants.findForUpdate(
            participant.getConnectionId(), session.tenantId(), session.sessionId()))
        .thenReturn(Optional.of(participant));

    var result =
        service.revoke(
            session.sessionId(),
            participant.getConnectionId(),
            session.tenantId(),
            "admin-test",
            "req-test",
            "idempotency-test");

    assertThat(result.state()).isEqualTo("REVOKE_REQUESTED");
    assertThat(result.revokedBy()).isEqualTo("admin-test");
    var command = ArgumentCaptor.forClass(io.browsercloud.coordinator.NodeCommand.class);
    verify(nodeCommands).send(command.capture());
    assertThat(command.getValue().commandType()).isEqualTo("RevokeRemoteDesktopConnection");
    assertThat(command.getValue().operationEpoch()).isZero();
    verify(audit).append(any());
  }

  @Test
  void shouldKeepRepeatedRevokeIdempotent() {
    var session = runningSession();
    var participant = participant("rdc_1234567890abcdefghij");
    participant.requestRevoke("admin-test", Instant.parse("2026-08-12T00:00:01Z"));
    when(sessions.requireForUpdate(session.sessionId())).thenReturn(session);
    when(participants.findForUpdate(
            participant.getConnectionId(), session.tenantId(), session.sessionId()))
        .thenReturn(Optional.of(participant));

    var result =
        service.revoke(
            session.sessionId(),
            participant.getConnectionId(),
            session.tenantId(),
            "admin-test",
            "req-repeat",
            "idempotency-repeat");

    assertThat(result.state()).isEqualTo("REVOKE_REQUESTED");
    verify(nodeCommands, never()).send(any());
    verify(audit, never()).append(any());
  }

  private RemoteDesktopParticipantEntity participant(String connectionId) {
    var participant =
        new RemoteDesktopParticipantEntity(
            connectionId,
            "tenant-test",
            "ses_1234567890abcdef",
            3,
            Instant.parse("2026-08-12T00:00:00Z"));
    participant.apply(
        3,
        "user-test",
        "COLLABORATIVE",
        false,
        "CONNECTED",
        "RFB_UPSTREAM_CONNECTED",
        "",
        Instant.parse("2026-08-12T00:00:00Z"));
    return participant;
  }

  private SessionContext runningSession() {
    var now = Instant.parse("2026-08-12T00:00:00Z");
    return new SessionContext(
        "ses_1234567890abcdef",
        "tenant-test",
        "profile-test",
        "node-test",
        "runtime-test",
        "isolation-test",
        "proxy-test",
        1,
        3,
        4,
        1,
        ResourceClass.L3,
        SessionState.RUNNING,
        "policy-hash",
        now,
        now);
  }
}
