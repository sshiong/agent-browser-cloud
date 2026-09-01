package io.browsercloud.application;

import static io.browsercloud.api.AgentClipboardBridgeModels.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.RemoteDesktopParticipantEntity;
import io.browsercloud.persistence.RemoteDesktopParticipantJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentClipboardBridgeApplicationServiceTest {
  private static final String SESSION_ID = "ses_1234567890abcdef";
  private static final String CONNECTION_ID = "rdc_1234567890abcdefghij";
  private final JdbcTemplate jdbc =
      Mockito.mock(
          JdbcTemplate.class,
          invocation ->
              invocation.getMethod().getName().equals("query")
                  ? List.of()
                  : Answers.RETURNS_DEFAULTS.answer(invocation));
  private final SessionRepository sessions = Mockito.mock(SessionRepository.class);
  private final RemoteDesktopParticipantJpaRepository participants =
      Mockito.mock(RemoteDesktopParticipantJpaRepository.class);
  private final AgentClipboardApplicationService clipboard =
      Mockito.mock(AgentClipboardApplicationService.class);
  private final AgentActionPayloadService payloads = Mockito.mock(AgentActionPayloadService.class);
  private final AuditApplicationService audit = Mockito.mock(AuditApplicationService.class);
  private final AgentClipboardBridgeApplicationService service =
      new AgentClipboardBridgeApplicationService(
          jdbc, sessions, participants, clipboard, payloads, audit);

  @BeforeEach
  void setUp() {
    when(sessions.requireForUpdate(SESSION_ID)).thenReturn(runningSession());
  }

  @Test
  void rejectsAgentToUserDeliveryThroughViewOnlyObservationConnection() {
    when(participants.findForUpdate(CONNECTION_ID, "tenant-test", SESSION_ID))
        .thenReturn(Optional.of(participant(true)));

    assertThatThrownBy(
            () ->
                service.create(
                    SESSION_ID,
                    "tenant-test",
                    "user-test",
                    "bridge-idempotency-1",
                    "request-test",
                    new CreateClipboardBridgeRequest(
                        ClipboardBridgeDirection.AGENT_TO_USER,
                        ClipboardBridgePurpose.HUMAN_ASSISTANCE,
                        CONNECTION_ID,
                        1,
                        null,
                        null)))
        .isInstanceOf(
            AgentClipboardBridgeApplicationService.AgentClipboardBridgeRejectedException.class)
        .hasMessage("CLIPBOARD_BRIDGE_CONTROL_CONNECTION_REQUIRED");

    verify(clipboard, never()).read(any(), any(), any());
  }

  @Test
  void rejectsStaleRfbClipboardBeforeChangingAgentClipboard() {
    when(participants.findForUpdate(CONNECTION_ID, "tenant-test", SESSION_ID))
        .thenReturn(Optional.of(participant(false)));

    assertThatThrownBy(
            () ->
                service.create(
                    SESSION_ID,
                    "tenant-test",
                    "user-test",
                    "bridge-idempotency-2",
                    "request-test",
                    new CreateClipboardBridgeRequest(
                        ClipboardBridgeDirection.USER_TO_AGENT,
                        ClipboardBridgePurpose.OPERATOR_COPY,
                        CONNECTION_ID,
                        0,
                        "ordinary clipboard text",
                        Instant.now().minusSeconds(180))))
        .isInstanceOf(
            AgentClipboardBridgeApplicationService.AgentClipboardBridgeRejectedException.class)
        .hasMessage("USER_CLIPBOARD_STALE");

    verify(clipboard, never()).write(any(), any(), any(), any());
  }

  private static RemoteDesktopParticipantEntity participant(boolean viewOnly) {
    var now = Instant.now();
    var participant =
        new RemoteDesktopParticipantEntity(
            CONNECTION_ID, "tenant-test", SESSION_ID, 3, now.minusSeconds(1));
    participant.apply(
        3, "user-test", "COLLABORATIVE", viewOnly, "CONNECTED", "RFB_UPSTREAM_CONNECTED", "", now);
    return participant;
  }

  private static SessionContext runningSession() {
    var now = Instant.now();
    return new SessionContext(
        SESSION_ID,
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
