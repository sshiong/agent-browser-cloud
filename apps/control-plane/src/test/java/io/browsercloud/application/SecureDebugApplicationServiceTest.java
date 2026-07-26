package io.browsercloud.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.BreakGlassRequestEntity;
import io.browsercloud.persistence.BreakGlassRequestJpaRepository;
import io.browsercloud.persistence.SecureDebugAccessEventEntity;
import io.browsercloud.persistence.SecureDebugAccessEventJpaRepository;
import io.browsercloud.persistence.SecureDebugSessionEntity;
import io.browsercloud.persistence.SecureDebugSessionJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SecureDebugApplicationServiceTest {

  @Mock private SecureDebugSessionJpaRepository sessionRepository;
  @Mock private SecureDebugAccessEventJpaRepository accessEventRepository;
  @Mock private BreakGlassRequestJpaRepository breakGlassRepository;
  @Mock private BreakGlassApplicationService breakGlassService;
  @Mock private SessionRepository browserSessionRepository;
  @Mock private BrowserStateRepository browserStateRepository;
  @Mock private AuditApplicationService auditService;

  private SecureDebugApplicationService service;

  @BeforeEach
  void setUp() {
    lenient()
        .when(sessionRepository.save(any(SecureDebugSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    service =
        new SecureDebugApplicationService(
            sessionRepository,
            accessEventRepository,
            breakGlassRepository,
            breakGlassService,
            browserSessionRepository,
            browserStateRepository,
            auditService,
            15);
  }

  @Test
  void startsSingleUseSessionOnlyForExactApprovedGrant() {
    var grant = grant();
    when(breakGlassRepository.findForUpdate(grant.getRequestId(), grant.getTenantId()))
        .thenReturn(Optional.of(grant));
    when(breakGlassService.authorize(
            grant.getRequestId(),
            grant.getTenantId(),
            grant.getRequestedBy(),
            grant.getResourceType(),
            grant.getResourceId(),
            grant.getRequestedScope()))
        .thenReturn(true);
    when(browserSessionRepository.require(grant.getResourceId())).thenReturn(browserSession());
    when(sessionRepository.findByBreakGlassRequestIdAndTenantId(
            grant.getRequestId(), grant.getTenantId()))
        .thenReturn(Optional.empty());

    var result = service.start(grant.getRequestId(), grant.getTenantId(), grant.getRequestedBy());

    assertEquals("ACTIVE", result.state());
    assertEquals(grant.getRequestId(), result.breakGlassRequestId());
    assertEquals(grant.getRequestedBy(), result.operatorId());
    assertTrue(result.expiresAt().isAfter(result.startedAt()));
    assertFalse(result.expiresAt().isAfter(result.startedAt().plusSeconds(15 * 60L)));
    verify(accessEventRepository).save(any());
    verify(auditService).append(any());
  }

  @Test
  void snapshotReturnsOnlyPurposeLimitedProjectionAndBuildsEvidenceChain() {
    var entity = debugSession();
    var grant = grant();
    when(sessionRepository.findForUpdate(entity.getDebugSessionId(), entity.getTenantId()))
        .thenReturn(Optional.of(entity));
    when(breakGlassService.authorize(
            entity.getBreakGlassRequestId(),
            entity.getTenantId(),
            entity.getOperatorId(),
            entity.getResourceType(),
            entity.getResourceId(),
            "SECURE_DEBUG"))
        .thenReturn(true);
    when(browserSessionRepository.require(entity.getResourceId())).thenReturn(browserSession());
    var state =
        new NodeEvent.StateUpdated(
            entity.getResourceId(),
            14,
            3,
            "https://example.test/private?token=secret#account",
            "Customer secret title",
            "state-hash",
            "FRESH",
            List.of(
                new NodeEvent.InteractiveTarget(
                    "target-a",
                    "textbox",
                    "Password",
                    new NodeEvent.Bounds(10, 20, 100, 30),
                    true,
                    true,
                    true),
                new NodeEvent.InteractiveTarget(
                    "target-b",
                    "button",
                    "Submit",
                    new NodeEvent.Bounds(20, 60, 80, 30),
                    true,
                    true,
                    false)));
    when(browserStateRepository.find(entity.getResourceId()))
        .thenReturn(
            Optional.of(new BrowserStateRepository.Snapshot(entity.getTenantId(), 7, state)));

    var first = service.snapshot(entity.getDebugSessionId(), entity.getTenantId(), "security-a");
    var second = service.snapshot(entity.getDebugSessionId(), entity.getTenantId(), "security-a");

    assertEquals("https://example.test", first.urlOrigin());
    assertFalse(first.urlOrigin().contains("token"));
    assertEquals(2, first.interactiveTargetCount());
    assertEquals(1, first.sensitiveTargetCount());
    assertEquals("SENSITIVE_MINIMIZED", first.dataClassification());
    assertFalse(first.fieldProjection().contains("title"));
    assertEquals(2, second.accessCount());
    assertNotEquals(first.accessEvidenceHash(), second.accessEvidenceHash());

    var events = ArgumentCaptor.forClass(SecureDebugAccessEventEntity.class);
    verify(accessEventRepository, times(2)).save(events.capture());
    assertNull(events.getAllValues().get(0).getPreviousEventHash());
    assertEquals(
        events.getAllValues().get(0).getEvidenceHash(),
        events.getAllValues().get(1).getPreviousEventHash());
  }

  @Test
  void revokedGrantImmediatelyClosesDataPlane() {
    var entity = debugSession();
    when(sessionRepository.findForUpdate(entity.getDebugSessionId(), entity.getTenantId()))
        .thenReturn(Optional.of(entity));
    when(breakGlassService.authorize(any(), any(), any(), any(), any(), any())).thenReturn(false);

    var error =
        assertThrows(
            SecureDebugApplicationService.SecureDebugRejectedException.class,
            () ->
                service.snapshot(
                    entity.getDebugSessionId(), entity.getTenantId(), entity.getOperatorId()));

    assertEquals("BREAK_GLASS_GRANT_NOT_AUTHORIZED", error.getMessage());
    assertEquals("REVOKED", entity.getState());
    assertEquals("BREAK_GLASS_GRANT_INVALID", entity.getEndReason());
    verify(browserStateRepository, never()).find(any());
    verify(accessEventRepository).save(any());
  }

  @Test
  void differentAdministratorCannotUseOperatorsDebugSession() {
    var entity = debugSession();
    when(sessionRepository.findForUpdate(entity.getDebugSessionId(), entity.getTenantId()))
        .thenReturn(Optional.of(entity));

    var error =
        assertThrows(
            SecureDebugApplicationService.SecureDebugRejectedException.class,
            () -> service.snapshot(entity.getDebugSessionId(), entity.getTenantId(), "security-b"));

    assertEquals("ONLY_AUTHORIZED_OPERATOR_MAY_ACCESS", error.getMessage());
    verifyNoInteractions(browserStateRepository, breakGlassService);
    verify(accessEventRepository).save(any());
    verify(auditService).append(any());
  }

  private static BreakGlassRequestEntity grant() {
    var now = Instant.now();
    var grant =
        new BreakGlassRequestEntity(
            "bgr_1234567890abcdefghij",
            "tenant-a",
            "INC-2026-007",
            "Investigate a production session with minimized diagnostics",
            "SESSION",
            "ses_1234567890abcdef",
            "SECURE_DEBUG",
            "security-a",
            now,
            now.plusSeconds(3600));
    grant.approve("security-b", "approval-evidence", now);
    return grant;
  }

  private static SecureDebugSessionEntity debugSession() {
    var now = Instant.now();
    return new SecureDebugSessionEntity(
        "dbg_1234567890abcdefghij",
        "bgr_1234567890abcdefghij",
        "tenant-a",
        "SESSION",
        "ses_1234567890abcdef",
        "security-a",
        now,
        now.plusSeconds(900));
  }

  private static SessionContext browserSession() {
    var now = Instant.now();
    return new SessionContext(
        "ses_1234567890abcdef",
        "tenant-a",
        "profile-a",
        "node-a",
        "runtime-126",
        "isolation-a",
        "proxy-a",
        2,
        7,
        4,
        3,
        ResourceClass.L2,
        SessionState.RUNNING,
        "policy-hash",
        now,
        now);
  }
}
