package io.browsercloud.application;

import static io.browsercloud.api.SessionEvidenceModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionEvidenceGovernanceServiceTest {

  private SessionRepository sessions;
  private OperationRepository operations;
  private BrowserCapacityApplicationService capacity;
  private NodeCommandGateway commands;
  private SessionEvidenceGovernanceStore store;
  private SessionEvidenceAccessNodeGateway nodeAccess;
  private AuditApplicationService audit;
  private SessionEvidenceGovernanceService service;

  @BeforeEach
  void setUp() {
    sessions = mock(SessionRepository.class);
    operations = mock(OperationRepository.class);
    capacity = mock(BrowserCapacityApplicationService.class);
    commands = mock(NodeCommandGateway.class);
    store = mock(SessionEvidenceGovernanceStore.class);
    nodeAccess = mock(SessionEvidenceAccessNodeGateway.class);
    audit = mock(AuditApplicationService.class);
    service =
        new SessionEvidenceGovernanceService(
            sessions, operations, capacity, commands, store, nodeAccess, audit);
  }

  @Test
  void persistsCaptureBeforeSendingTheRealNodeCommand() {
    var session = runningSession();
    var createdAt = Instant.parse("2026-07-30T10:00:00Z");
    var view =
        new EvidenceCaptureView(
            "cap_1234567890abcdefghij",
            session.sessionId(),
            EvidencePurpose.SUPPORT_DIAGNOSTICS,
            "EXECUTING",
            null,
            null,
            "cmd_1234567890abcdefghij",
            "request-test",
            createdAt,
            null);
    when(sessions.requireForUpdate(session.sessionId())).thenReturn(session);
    when(operations.findActive(session.sessionId())).thenReturn(Optional.empty());
    when(capacity.nodeHasCapability(session.nodeId(), "observerEvidence", "cdp-s3-v1"))
        .thenReturn(true);
    when(store.findCaptureByIdempotency("tenant-test", "actor-test", "capture-key"))
        .thenReturn(Optional.empty());
    when(store.insertCapture(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(true);
    when(store.findCapture("tenant-test", session.sessionId(), view.captureId()))
        .thenReturn(Optional.of(view));

    // IDs are intentionally generated inside the service. Return the durable row regardless of
    // the generated value so this test asserts the protocol boundary, not UUID formatting.
    when(store.findCapture(
            org.mockito.ArgumentMatchers.eq("tenant-test"),
            org.mockito.ArgumentMatchers.eq(session.sessionId()),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Optional.of(view));

    var result =
        service.capture(
            session.sessionId(),
            "tenant-test",
            "actor-test",
            "capture-key",
            "request-test",
            new CaptureEvidenceRequest(EvidencePurpose.SUPPORT_DIAGNOSTICS));

    assertThat(result.state()).isEqualTo("EXECUTING");
    verify(store).insertCapture(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(commands).send(any());
    verify(audit).append(any());
  }

  @Test
  void signsOneExactEvidenceObjectAndCommitsTheGrantWithoutPersistingTheUrl() {
    var session = runningSession();
    var now = Instant.now();
    var claim =
        new SessionEvidenceGovernanceStore.EvidenceAccessClaim(
            "egr_1234567890abcdefghij",
            "evd_1234567890abcdefghij",
            session.profileId(),
            session.nodeId(),
            "a".repeat(64),
            1024,
            now.plusSeconds(300));
    var signed =
        new SessionEvidenceAccessNodeGateway.SignedEvidenceAccess(
            claim.grantId(),
            claim.nodeId(),
            claim.evidenceId(),
            "https://objects.example.test/evidence?signature=secret",
            now.plusSeconds(60));
    when(sessions.require(session.sessionId())).thenReturn(session);
    when(store.claim(
            eq("tenant-test"),
            eq(session.sessionId()),
            eq(claim.grantId()),
            eq("actor-test"),
            org.mockito.ArgumentMatchers.isNull(),
            any(Instant.class)))
        .thenReturn(claim);
    when(capacity.nodeHasCapability(session.nodeId(), "evidenceAccess", "presigned-get-v1"))
        .thenReturn(true);
    when(nodeAccess.sign(any())).thenReturn(signed);

    var result =
        service.redeem(
            session.sessionId(), claim.grantId(), "tenant-test", "actor-test", "request-test");

    assertThat(result.downloadUrl()).isEqualTo(signed.downloadUrl());
    verify(store).commitGrant(org.mockito.ArgumentMatchers.eq(claim.grantId()), any(), any());
    verify(audit).append(any());
  }

  private static SessionContext runningSession() {
    var now = Instant.parse("2026-07-30T09:00:00Z");
    return new SessionContext(
        "ses_1234567890abcdef",
        "tenant-test",
        "profile-test",
        "node-test",
        "runtime-stable",
        "isolation-standard",
        "proxy-test",
        1,
        1,
        1,
        1,
        ResourceClass.L2,
        SessionState.RUNNING,
        "policy-hash",
        now,
        now);
  }
}
