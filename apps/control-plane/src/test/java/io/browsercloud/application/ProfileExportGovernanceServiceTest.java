package io.browsercloud.application;

import static io.browsercloud.api.ProfileExportModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.persistence.BrowserNodeEntity;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.persistence.ProfileEntity;
import io.browsercloud.persistence.ProfileJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileExportGovernanceServiceTest {

  private ProfileJpaRepository profiles;
  private BrowserNodeJpaRepository nodes;
  private ProfileExportGovernanceStore store;
  private ProfileExportAccessNodeGateway nodeAccess;
  private AuditApplicationService audit;
  private ProfileExportGovernanceService service;

  @BeforeEach
  void setUp() {
    profiles = mock(ProfileJpaRepository.class);
    nodes = mock(BrowserNodeJpaRepository.class);
    store = mock(ProfileExportGovernanceStore.class);
    nodeAccess = mock(ProfileExportAccessNodeGateway.class);
    audit = mock(AuditApplicationService.class);
    service = new ProfileExportGovernanceService(profiles, nodes, store, nodeAccess, audit);
  }

  @Test
  void grantsTheExactLatestCheckpointAndReusesOnlyAnIdenticalIdempotentRequest() {
    var profile = checkpointedProfile();
    var view =
        new ProfileExportGrantView(
            "pxg_1234567890abcdefghij",
            profile.getProfileId(),
            profile.getLatestCheckpointId(),
            profile.getLatestCheckpointEpochOrZero(),
            ProfileExportPurpose.TENANT_BACKUP,
            "ISSUED",
            Instant.now().plusSeconds(300),
            Instant.now(),
            null,
            null,
            null,
            null,
            "request-test");
    when(profiles.findById(profile.getProfileId())).thenReturn(Optional.of(profile));
    when(store.findByIdempotency("tenant-test", "actor-test", "export-key"))
        .thenReturn(Optional.empty(), Optional.of(view));
    when(store.insertGrant(
            any(), any(), any(), any(), any(Long.class), any(), any(), any(), any(), any(), any()))
        .thenReturn(true);

    var created =
        service.createGrant(
            profile.getProfileId(),
            "tenant-test",
            "actor-test",
            "export-key",
            "request-test",
            new CreateProfileExportGrantRequest(ProfileExportPurpose.TENANT_BACKUP));

    assertThat(created.checkpointId()).isEqualTo(profile.getLatestCheckpointId());
    verify(store)
        .insertGrant(
            any(),
            eq("tenant-test"),
            eq(profile.getProfileId()),
            eq(profile.getLatestCheckpointId()),
            eq(profile.getLatestCheckpointEpochOrZero()),
            eq("actor-test"),
            eq(ProfileExportPurpose.TENANT_BACKUP),
            eq("export-key"),
            eq("request-test"),
            any(),
            any());
    verify(audit).append(any());
  }

  @Test
  void redeemsOnceThroughAHealthyExporterAndPersistsOnlyArchiveFacts() {
    var profile = checkpointedProfile();
    var claim =
        new ProfileExportGovernanceStore.ProfileExportClaim(
            "pxg_1234567890abcdefghij",
            profile.getProfileId(),
            profile.getLatestCheckpointId(),
            profile.getLatestCheckpointEpochOrZero(),
            Instant.now().plusSeconds(300));
    var node = mock(BrowserNodeEntity.class);
    when(node.getNodeId()).thenReturn("node-test");
    when(profiles.findById(profile.getProfileId())).thenReturn(Optional.of(profile));
    when(store.claim(
            eq("tenant-test"),
            eq(profile.getProfileId()),
            eq(claim.grantId()),
            eq("actor-test"),
            any()))
        .thenReturn(claim);
    when(nodes.findProfileExportCandidates(any())).thenReturn(List.of(node));
    when(nodeAccess.sign(any()))
        .thenReturn(
            new ProfileExportAccessNodeGateway.SignedProfileExport(
                claim.grantId(),
                "node-test",
                profile.getProfileId(),
                profile.getLatestCheckpointId(),
                "a".repeat(64),
                4096,
                "https://objects.example.test/checkpoint?signature=secret",
                Instant.now().plusSeconds(60)));

    var result =
        service.redeem(
            profile.getProfileId(), claim.grantId(), "tenant-test", "actor-test", "request-test");

    assertThat(result.downloadUrl()).contains("signature=secret");
    verify(store)
        .commitGrant(eq(claim.grantId()), eq("node-test"), eq("a".repeat(64)), eq(4096L), any());
  }

  @Test
  void failsClosedWhenTheProfileAdvancedAfterGrantIssuance() {
    var profile = checkpointedProfile();
    var claim =
        new ProfileExportGovernanceStore.ProfileExportClaim(
            "pxg_1234567890abcdefghij",
            profile.getProfileId(),
            "chk_older1234567890",
            1,
            Instant.now().plusSeconds(300));
    when(profiles.findById(profile.getProfileId())).thenReturn(Optional.of(profile));
    when(store.claim(any(), any(), any(), any(), any())).thenReturn(claim);

    assertThatThrownBy(
            () ->
                service.redeem(
                    profile.getProfileId(),
                    claim.grantId(),
                    "tenant-test",
                    "actor-test",
                    "request-test"))
        .isInstanceOf(ProfileExportGovernanceService.ProfileExportRejectedException.class)
        .hasMessage("PROFILE_CHECKPOINT_CHANGED");
    verify(store).failGrant(eq(claim.grantId()), eq("PROFILE_CHECKPOINT_CHANGED"), any());
  }

  private static ProfileEntity checkpointedProfile() {
    var now = Instant.parse("2026-08-13T00:00:00Z");
    var profile =
        new ProfileEntity("profile-test", "tenant-test", "Test Profile", null, "storage-path", now);
    profile.commitCheckpoint("chk_1234567890abcdef", 2, 7, 4096, 12, "EMPTY", now.plusSeconds(1));
    return profile;
  }
}
