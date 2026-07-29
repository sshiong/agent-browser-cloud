package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.application.ProfileImportApplicationService.ProfileImportRejectedException;
import io.browsercloud.application.ProfileImportJobStore.ProfileImportClaim;
import io.browsercloud.application.ProfileImportNodeGateway.ProfileImportNodeRequest;
import io.browsercloud.application.ProfileImportNodeGateway.ProfileImportNodeResult;
import io.browsercloud.infrastructure.GrpcProfileImportNodeGateway.ProfileImportNodeRejectedException;
import io.browsercloud.persistence.ProfileImportJobEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class ProfileImportApplicationServiceTest {

  @Mock private ProfileImportJobStore store;
  @Mock private ProfileImportNodeGateway nodeGateway;
  @Mock private RuntimeBuildPolicy runtimeBuildPolicy;

  private ProfileImportApplicationService service;

  @BeforeEach
  void setUp() {
    service = new ProfileImportApplicationService(store, nodeGateway, runtimeBuildPolicy);
  }

  @Test
  void streamsArchiveAndReturnsDurableCommittedIdentifiers() throws Exception {
    var bytes = "bounded-checkpoint".getBytes(StandardCharsets.UTF_8);
    var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var archive = new MockMultipartFile("archive", "profile.tar.zst", "application/zstd", bytes);
    var job = requestedJob(hash, bytes.length);
    when(store.claim(any(ProfileImportClaim.class))).thenReturn(job);
    when(store.begin(job.getImportId(), "tenant-a", "operator-a")).thenReturn(job);
    when(nodeGateway.upload(any(ProfileImportNodeRequest.class), any()))
        .thenAnswer(
            invocation -> {
              ProfileImportNodeRequest request = invocation.getArgument(0);
              assertThat(invocation.<java.io.InputStream>getArgument(1).readAllBytes())
                  .isEqualTo(bytes);
              return new ProfileImportNodeResult(
                  request.importId(),
                  "node-import",
                  request.profileId(),
                  request.checkpointId(),
                  1,
                  0,
                  4096,
                  3,
                  request.archiveSha256(),
                  request.archiveSizeBytes());
            });
    when(store.commit(eq(job.getImportId()), eq("tenant-a"), eq("operator-a"), any()))
        .thenAnswer(
            invocation -> {
              ProfileImportNodeResult result = invocation.getArgument(3);
              job.committed(
                  result.nodeId(),
                  result.checkpointEpoch(),
                  result.profileWriteEpoch(),
                  result.coreSizeBytes(),
                  result.checkpointFileCount(),
                  Instant.parse("2026-07-30T00:01:00Z"));
              return ProfileImportJobStore.toView(job);
            });

    var result =
        service.importCheckpoint(
            "tenant-a",
            "operator-a",
            "import-key",
            "request-1",
            "profile-imported",
            "Imported CRM",
            null,
            "runtime-stable",
            hash,
            archive);

    assertThat(result.state()).isEqualTo("COMMITTED");
    assertThat(result.operationId()).isEqualTo("op_1234567890abcdef");
    assertThat(result.checkpointId()).isEqualTo("chk_1234567890abcdef");
    verify(runtimeBuildPolicy).requireApproved("runtime-stable");
    verify(store).validating(job.getImportId(), "tenant-a", "operator-a");
  }

  @Test
  void rejectsInvalidHashBeforeClaimingOrOpeningDataPlane() {
    var archive =
        new MockMultipartFile(
            "archive",
            "profile.tar.zst",
            "application/zstd",
            "content".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(
            () ->
                service.importCheckpoint(
                    "tenant-a",
                    "operator-a",
                    "import-key",
                    "request-1",
                    "profile-imported",
                    "Imported CRM",
                    null,
                    "runtime-stable",
                    "not-a-sha",
                    archive))
        .isInstanceOf(ProfileImportRejectedException.class)
        .hasMessage("PROFILE_IMPORT_SHA256_INVALID");

    verify(store, never()).claim(any());
    verify(nodeGateway, never()).upload(any(), any());
  }

  @Test
  void recordsStableFailureWhenNodeRejectsArchive() {
    var bytes = "invalid-checkpoint".getBytes(StandardCharsets.UTF_8);
    var hash = PromptSecurityService.sha256("invalid-checkpoint");
    var archive = new MockMultipartFile("archive", "profile.tar.zst", "application/zstd", bytes);
    var job = requestedJob(hash, bytes.length);
    when(store.claim(any(ProfileImportClaim.class))).thenReturn(job);
    when(store.begin(job.getImportId(), "tenant-a", "operator-a")).thenReturn(job);
    when(nodeGateway.upload(any(), any()))
        .thenThrow(new ProfileImportNodeRejectedException("PROFILE_IMPORT_ARCHIVE_UNSAFE"));

    assertThatThrownBy(
            () ->
                service.importCheckpoint(
                    "tenant-a",
                    "operator-a",
                    "import-key",
                    "request-1",
                    "profile-imported",
                    "Imported CRM",
                    null,
                    "runtime-stable",
                    hash,
                    archive))
        .isInstanceOf(ProfileImportRejectedException.class)
        .hasMessage("PROFILE_IMPORT_ARCHIVE_UNSAFE");

    verify(store)
        .fail(job.getImportId(), "tenant-a", "operator-a", "PROFILE_IMPORT_ARCHIVE_UNSAFE");
    verify(store, never()).commit(any(), any(), any(), any());
  }

  @Test
  void committedIdempotentReplayDoesNotUploadAgain() {
    var bytes = "checkpoint".getBytes(StandardCharsets.UTF_8);
    var hash = PromptSecurityService.sha256("checkpoint");
    var archive = new MockMultipartFile("archive", "profile.tar.zst", "application/zstd", bytes);
    var job = requestedJob(hash, bytes.length);
    job.committed("node-import", 1, 0, 1024, 1, Instant.parse("2026-07-30T00:01:00Z"));
    when(store.claim(any(ProfileImportClaim.class))).thenReturn(job);

    var result =
        service.importCheckpoint(
            "tenant-a",
            "operator-a",
            "import-key",
            "request-1",
            "profile-imported",
            "Imported CRM",
            null,
            "runtime-stable",
            hash,
            archive);

    assertThat(result.state()).isEqualTo("COMMITTED");
    verify(store, never()).begin(any(), any(), any());
    verify(nodeGateway, never()).upload(any(), any());
    verify(runtimeBuildPolicy, never()).requireApproved(any());
  }

  private static ProfileImportJobEntity requestedJob(String hash, long size) {
    return new ProfileImportJobEntity(
        "pim_1234567890abcdef",
        "tenant-a",
        "operator-a",
        "import-key",
        "b".repeat(64),
        "request-1",
        "op_1234567890abcdef",
        "profile-imported",
        "Imported CRM",
        null,
        "runtime-stable",
        hash,
        size,
        "chk_1234567890abcdef",
        null,
        Instant.parse("2026-07-30T00:00:00Z"));
  }
}
