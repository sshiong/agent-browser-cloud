package io.browsercloud.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.browsercloud.persistence.RuntimeBuildEntity;
import io.browsercloud.persistence.RuntimeBuildJpaRepository;
import io.browsercloud.persistence.RuntimeReleaseRequestEntity;
import io.browsercloud.persistence.RuntimeReleaseRequestJpaRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RuntimeReleaseApplicationServiceTest {

  @Mock private RuntimeReleaseRequestJpaRepository releaseRepository;
  @Mock private RuntimeBuildJpaRepository buildRepository;
  @Mock private RuntimeBuildPolicy buildPolicy;
  @Mock private AuditApplicationService auditService;

  private RuntimeReleaseApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new RuntimeReleaseApplicationService(
            releaseRepository, buildRepository, buildPolicy, auditService);
  }

  @Test
  void createsValidatedPromotionRequestAndAppendsReleaseAudit() {
    var view =
        service.requestPromotion(
            "platform", "release-a", "runtime-126", "CANARY", "Promote validated build to canary");

    assertEquals("REQUESTED", view.state());
    assertEquals("CANARY", view.targetChannel());
    verify(buildPolicy).requireReleaseCandidate("runtime-126");
    verify(releaseRepository).saveAndFlush(any(RuntimeReleaseRequestEntity.class));
    verify(auditService).append(any());
  }

  @Test
  void requesterCannotApproveOwnRelease() {
    var request = requestEntity("CANARY");
    when(releaseRepository.findForUpdate(request.getReleaseId(), request.getTenantId()))
        .thenReturn(Optional.of(request));

    var error =
        assertThrows(
            RuntimeReleaseApplicationService.RuntimeReleaseRejectedException.class,
            () -> service.approve(request.getReleaseId(), request.getTenantId(), "release-a"));

    assertEquals("REQUESTER_CANNOT_APPROVE", error.getMessage());
    assertEquals("REQUESTED", request.getState());
    verify(auditService).appendIndependent(any());
    verifyNoInteractions(buildRepository);
  }

  @Test
  void secondPlatformAdminReleasesBuildWithEvidence() {
    var request = requestEntity("STABLE");
    var build = mock(RuntimeBuildEntity.class);
    when(build.getBuildId()).thenReturn("runtime-126");
    when(releaseRepository.findForUpdate(request.getReleaseId(), request.getTenantId()))
        .thenReturn(Optional.of(request));
    when(buildRepository.findForUpdate("runtime-126")).thenReturn(Optional.of(build));

    var view = service.approve(request.getReleaseId(), request.getTenantId(), "release-b");

    assertEquals("APPROVED", view.state());
    assertEquals("release-b", view.approvedBy());
    assertEquals(64, view.evidenceHash().length());
    verify(buildPolicy).requireReleaseCandidate("runtime-126");
    verify(build).release(eq("STABLE"), any());
    verify(buildRepository).save(build);
    verify(auditService).append(any());
  }

  private static RuntimeReleaseRequestEntity requestEntity(String targetChannel) {
    return new RuntimeReleaseRequestEntity(
        "rel_1234567890abcdefghij",
        "platform",
        "runtime-126",
        targetChannel,
        "Promote validated runtime after reviewing all evidence",
        "release-a",
        Instant.now());
  }
}
