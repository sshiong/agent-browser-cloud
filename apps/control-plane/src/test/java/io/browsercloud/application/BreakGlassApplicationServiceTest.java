package io.browsercloud.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.browsercloud.api.CreateBreakGlassRequest;
import io.browsercloud.persistence.BreakGlassRequestEntity;
import io.browsercloud.persistence.BreakGlassRequestJpaRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BreakGlassApplicationServiceTest {

  @Mock private BreakGlassRequestJpaRepository repository;
  @Mock private AuditApplicationService auditService;

  private BreakGlassApplicationService service;

  @BeforeEach
  void setUp() {
    service = new BreakGlassApplicationService(repository, auditService);
  }

  @Test
  void createsTimeBoundRedactedRequestAndAppendsAdminAudit() {
    var result =
        service.request(
            "tenant-a",
            "security-a",
            new CreateBreakGlassRequest(
                "INC-2026-001",
                "Investigate password=secret customer incident",
                "SESSION",
                "ses_1234567890abcdef",
                "SECURE_DEBUG",
                30));

    var entity = ArgumentCaptor.forClass(BreakGlassRequestEntity.class);
    verify(repository).save(entity.capture());
    assertEquals("REQUESTED", result.state());
    assertEquals("security-a", result.requestedBy());
    assertTrue(result.reason().contains("password=[REDACTED]"));
    assertFalse(result.reason().contains("secret"));
    assertEquals(
        30,
        ChronoUnit.MINUTES.between(
            entity.getValue().getRequestedAt(), entity.getValue().getExpiresAt()));
    verify(auditService).append(any());
  }

  @Test
  void requesterCannotSelfApprove() {
    var entity = requestEntity(Instant.now(), Instant.now().plusSeconds(1800));
    when(repository.findForUpdate(entity.getRequestId(), entity.getTenantId()))
        .thenReturn(Optional.of(entity));

    var error =
        assertThrows(
            BreakGlassApplicationService.BreakGlassRejectedException.class,
            () -> service.approve(entity.getRequestId(), entity.getTenantId(), "security-a"));

    assertEquals("REQUESTER_CANNOT_APPROVE", error.getMessage());
    assertEquals("REQUESTED", entity.getState());
    verify(repository, never()).save(any());
    verify(auditService).appendIndependent(any());
  }

  @Test
  void expiredRequestIsRevokedBeforeApprovalIsRejected() {
    var entity = requestEntity(Instant.now().minusSeconds(600), Instant.now().minusSeconds(1));
    when(repository.findForUpdate(entity.getRequestId(), entity.getTenantId()))
        .thenReturn(Optional.of(entity));

    var error =
        assertThrows(
            BreakGlassApplicationService.BreakGlassRejectedException.class,
            () -> service.approve(entity.getRequestId(), entity.getTenantId(), "security-b"));

    assertEquals("REQUEST_EXPIRED", error.getMessage());
    assertEquals("EXPIRED", entity.getState());
    assertEquals("system", entity.getRevokedBy());
    verify(repository).save(entity);
    verify(auditService).append(any());
  }

  @Test
  void secondSecurityAdminActivatesOnlyTheRequestedActorAndExactScope() {
    var entity = requestEntity(Instant.now(), Instant.now().plusSeconds(1800));
    when(repository.findForUpdate(entity.getRequestId(), entity.getTenantId()))
        .thenReturn(Optional.of(entity));

    var active = service.approve(entity.getRequestId(), entity.getTenantId(), "security-b");

    assertEquals("ACTIVE", active.state());
    assertEquals("security-b", active.approvedBy());
    assertNotNull(active.approvedAt());
    assertNotNull(active.evidenceHash());
    assertEquals(64, active.evidenceHash().length());
    assertTrue(
        service.authorize(
            entity.getRequestId(),
            entity.getTenantId(),
            "security-a",
            "SESSION",
            "ses_1234567890abcdef",
            "SECURE_DEBUG"));
    assertFalse(
        service.authorize(
            entity.getRequestId(),
            entity.getTenantId(),
            "security-b",
            "SESSION",
            "ses_1234567890abcdef",
            "SECURE_DEBUG"));
    verify(repository, atLeastOnce()).save(entity);
    verify(auditService, atLeast(3)).append(any());
  }

  @Test
  void scheduledScannerAutomaticallyRevokesExpiredGrant() {
    var now = Instant.now();
    var entity = requestEntity(now.minusSeconds(3600), now.minusSeconds(1));
    entity.approve("security-b", "evidence", now.minusSeconds(3500));
    when(repository.findExpiredActiveForUpdate(any())).thenReturn(List.of(entity));

    service.expireActiveGrants();

    assertEquals("EXPIRED", entity.getState());
    assertEquals("system", entity.getRevokedBy());
    assertNotNull(entity.getRevokedAt());
    verify(repository).save(entity);
    verify(auditService).append(any());
  }

  private static BreakGlassRequestEntity requestEntity(Instant requestedAt, Instant expiresAt) {
    return new BreakGlassRequestEntity(
        "bgr_1234567890abcdefghij",
        "tenant-a",
        "INC-2026-001",
        "A sufficiently detailed incident access reason",
        "SESSION",
        "ses_1234567890abcdef",
        "SECURE_DEBUG",
        "security-a",
        requestedAt,
        expiresAt);
  }
}
