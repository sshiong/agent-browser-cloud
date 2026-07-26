package io.browsercloud.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.browsercloud.api.CompleteKeyRotationRequest;
import io.browsercloud.api.CreateKeyRotationRequest;
import io.browsercloud.persistence.KeyRotationRequestEntity;
import io.browsercloud.persistence.KeyRotationRequestJpaRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KeyRotationApplicationServiceTest {

  @Mock private KeyRotationRequestJpaRepository repository;
  @Mock private AuditApplicationService auditService;

  private KeyRotationApplicationService service;

  @BeforeEach
  void setUp() {
    service = new KeyRotationApplicationService(repository, auditService);
  }

  @Test
  void createsRedactedRotationRequestAndAudit() {
    var view =
        service.request(
            "platform-control",
            "key-admin-a",
            new CreateKeyRotationRequest(
                "NODE_MTLS",
                "node-ca-v1",
                "node-ca-v2",
                "SCHEDULED",
                "Rotate password=secret before certificate expiry",
                30));

    assertEquals("REQUESTED", view.state());
    assertEquals(30, view.requestedOverlapMinutes());
    assertTrue(view.reason().contains("password=[REDACTED]"));
    verify(repository).saveAndFlush(any(KeyRotationRequestEntity.class));
    verify(auditService).append(any());
  }

  @Test
  void requesterCannotApproveOwnRotation() {
    var entity = rotationEntity("SCHEDULED", 30);
    when(repository.findForUpdate(entity.getRotationId(), entity.getTenantId()))
        .thenReturn(Optional.of(entity));

    var error =
        assertThrows(
            KeyRotationApplicationService.KeyRotationRejectedException.class,
            () -> service.approve(entity.getRotationId(), entity.getTenantId(), "key-admin-a"));

    assertEquals("REQUESTER_CANNOT_APPROVE", error.getMessage());
    assertEquals("REQUESTED", entity.getState());
    verify(auditService).appendIndependent(any());
  }

  @Test
  void scheduledRotationRequiresOverlapAndAllVerificationChecks() {
    var entity = rotationEntity("SCHEDULED", 30);
    when(repository.findForUpdate(entity.getRotationId(), entity.getTenantId()))
        .thenReturn(Optional.of(entity));
    service.approve(entity.getRotationId(), entity.getTenantId(), "key-admin-b");

    var error =
        assertThrows(
            KeyRotationApplicationService.KeyRotationRejectedException.class,
            () ->
                service.complete(
                    entity.getRotationId(),
                    entity.getTenantId(),
                    "key-operator",
                    new CompleteKeyRotationRequest(true, true, true, 2, "probe/run-123")));

    assertEquals("VERIFIER_OVERLAP_NOT_ELAPSED", error.getMessage());
    assertEquals("ROTATING", entity.getState());
    verify(auditService).appendIndependent(any());
  }

  @Test
  void emergencyRotationCompletesWithoutOldKeyReadButRejectsPlaintext() {
    var entity = rotationEntity("SUSPECTED_COMPROMISE", 120);
    when(repository.findForUpdate(entity.getRotationId(), entity.getTenantId()))
        .thenReturn(Optional.of(entity));
    service.approve(entity.getRotationId(), entity.getTenantId(), "key-admin-b");

    var view =
        service.complete(
            entity.getRotationId(),
            entity.getTenantId(),
            "incident-commander",
            new CompleteKeyRotationRequest(true, false, true, 4, "incident/INC-2026-KEY"));

    assertEquals("COMPLETED", view.state());
    assertEquals(100, view.progressPercent());
    assertEquals(120, view.requestedOverlapMinutes());
    assertFalse(view.overlapUntil().isAfter(view.completedAt()));
    assertEquals(64, view.approvalEvidenceHash().length());
    assertEquals(64, view.completionEvidenceHash().length());
    verify(repository, atLeast(2)).save(entity);
  }

  private static KeyRotationRequestEntity rotationEntity(
      String rotationTrigger, int overlapMinutes) {
    return new KeyRotationRequestEntity(
        "rot_1234567890abcdefghij",
        "platform-control",
        "NODE_MTLS",
        "node-ca-v1",
        "node-ca-v2",
        rotationTrigger,
        "Rotate the node certificate authority safely",
        overlapMinutes,
        "key-admin-a",
        Instant.now());
  }
}
