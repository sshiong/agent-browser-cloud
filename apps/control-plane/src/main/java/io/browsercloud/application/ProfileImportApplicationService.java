package io.browsercloud.application;

import static io.browsercloud.api.ProfileImportModels.ProfileImportListResponse;
import static io.browsercloud.api.ProfileImportModels.ProfileImportView;

import io.browsercloud.application.ProfileImportJobStore.ProfileImportClaim;
import io.browsercloud.infrastructure.GrpcProfileImportNodeGateway.ProfileImportNodeRejectedException;
import io.browsercloud.infrastructure.GrpcProfileImportNodeGateway.ProfileImportNodeUnavailableException;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Orchestrates the bounded Profile archive stream outside database transactions. */
@Service
public class ProfileImportApplicationService {

  public static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;

  private final ProfileImportJobStore store;
  private final ProfileImportNodeGateway nodeGateway;
  private final RuntimeBuildPolicy runtimeBuildPolicy;

  public ProfileImportApplicationService(
      ProfileImportJobStore store,
      ProfileImportNodeGateway nodeGateway,
      RuntimeBuildPolicy runtimeBuildPolicy) {
    this.store = store;
    this.nodeGateway = nodeGateway;
    this.runtimeBuildPolicy = runtimeBuildPolicy;
  }

  public ProfileImportView importCheckpoint(
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      String profileId,
      String profileName,
      String profileDescription,
      String runtimeBuildId,
      String archiveSha256,
      MultipartFile archive) {
    var normalizedName = profileName == null ? "" : profileName.strip();
    var normalizedDescription =
        profileDescription == null || profileDescription.isBlank()
            ? null
            : profileDescription.strip();
    var normalizedHash =
        archiveSha256 == null ? "" : archiveSha256.strip().toLowerCase(Locale.ROOT);
    validateMetadata(
        idempotencyKey,
        profileId,
        normalizedName,
        normalizedDescription,
        runtimeBuildId,
        normalizedHash,
        archive);
    var requestHash =
        PromptSecurityService.sha256(
            String.join(
                "|",
                profileId,
                normalizedName,
                normalizedDescription == null ? "" : normalizedDescription,
                runtimeBuildId,
                normalizedHash,
                Long.toString(archive.getSize())));
    var candidateImportId = newId("pim_");
    var job =
        store.claim(
            new ProfileImportClaim(
                candidateImportId,
                tenantId,
                actorId,
                idempotencyKey,
                requestHash,
                requestId,
                newId("op_"),
                profileId,
                normalizedName,
                normalizedDescription,
                runtimeBuildId,
                normalizedHash,
                archive.getSize(),
                "chk_" + UUID.randomUUID().toString().replace("-", "")));
    if ("COMMITTED".equals(job.getState())) return ProfileImportJobStore.toView(job);
    runtimeBuildPolicy.requireApproved(runtimeBuildId);
    job = store.begin(job.getImportId(), tenantId, actorId);
    if ("COMMITTED".equals(job.getState())) return ProfileImportJobStore.toView(job);

    try (var input = archive.getInputStream()) {
      var result =
          nodeGateway.upload(
              new ProfileImportNodeGateway.ProfileImportNodeRequest(
                  job.getImportId(),
                  tenantId,
                  profileId,
                  job.getCheckpointId(),
                  runtimeBuildId,
                  normalizedHash,
                  archive.getSize()),
              input);
      store.validating(job.getImportId(), tenantId, actorId);
      return store.commit(job.getImportId(), tenantId, actorId, result);
    } catch (ProfileImportNodeRejectedException exception) {
      fail(job.getImportId(), tenantId, actorId, stableCode(exception, "PROFILE_IMPORT_REJECTED"));
      throw new ProfileImportRejectedException(
          stableCode(exception, "PROFILE_IMPORT_REJECTED"), exception);
    } catch (ProfileImportNodeUnavailableException exception) {
      fail(
          job.getImportId(),
          tenantId,
          actorId,
          stableCode(exception, "PROFILE_IMPORT_NODE_FAILED"));
      throw new ProfileImportUnavailableException(
          stableCode(exception, "PROFILE_IMPORT_NODE_FAILED"), exception);
    } catch (IOException exception) {
      fail(job.getImportId(), tenantId, actorId, "PROFILE_IMPORT_STREAM_FAILED");
      throw new ProfileImportRejectedException("PROFILE_IMPORT_STREAM_FAILED", exception);
    } catch (RuntimeException exception) {
      if (exception instanceof ProfileImportJobStore.ProfileImportConflictException) {
        fail(job.getImportId(), tenantId, actorId, exception.getMessage());
      } else {
        fail(job.getImportId(), tenantId, actorId, "PROFILE_IMPORT_COMMIT_FAILED");
      }
      throw exception;
    }
  }

  public ProfileImportView get(String importId, String tenantId, String actorId) {
    return store.get(importId, tenantId, actorId);
  }

  public ProfileImportListResponse list(String tenantId, String actorId, int limit) {
    return store.list(tenantId, actorId, limit);
  }

  private void fail(String importId, String tenantId, String actorId, String code) {
    try {
      store.fail(importId, tenantId, actorId, code);
    } catch (RuntimeException ignored) {
      // Preserve the original data-plane failure. A stale in-progress job is retryable after
      // the bounded abandonment window.
    }
  }

  private static void validateMetadata(
      String idempotencyKey,
      String profileId,
      String profileName,
      String profileDescription,
      String runtimeBuildId,
      String archiveSha256,
      MultipartFile archive) {
    if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
      throw new ProfileImportRejectedException("PROFILE_IMPORT_IDEMPOTENCY_KEY_INVALID");
    }
    if (!identifier(profileId)
        || !identifier(runtimeBuildId)
        || profileName.isEmpty()
        || profileName.length() > 128
        || (profileDescription != null && profileDescription.length() > 1024)) {
      throw new ProfileImportRejectedException("PROFILE_IMPORT_METADATA_INVALID");
    }
    if (archiveSha256.length() != 64
        || !archiveSha256.chars().allMatch(ProfileImportApplicationService::isLowerHex)) {
      throw new ProfileImportRejectedException("PROFILE_IMPORT_SHA256_INVALID");
    }
    if (archive == null || archive.isEmpty() || archive.getSize() > MAX_ARCHIVE_BYTES) {
      throw new ProfileImportRejectedException("PROFILE_IMPORT_ARCHIVE_SIZE_INVALID");
    }
  }

  private static boolean identifier(String value) {
    return value != null
        && !value.isBlank()
        && value.length() <= 128
        && value
            .chars()
            .allMatch(
                character ->
                    Character.isLetterOrDigit(character) || character == '_' || character == '-');
  }

  private static boolean isLowerHex(int character) {
    return (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f');
  }

  private static String stableCode(RuntimeException exception, String fallback) {
    var value = exception.getMessage();
    return value != null && value.matches("^[A-Z0-9_]{1,64}$") ? value : fallback;
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  public static final class ProfileImportRejectedException extends RuntimeException {
    public ProfileImportRejectedException(String code) {
      super(code);
    }

    public ProfileImportRejectedException(String code, Throwable cause) {
      super(code, cause);
    }
  }

  public static final class ProfileImportUnavailableException extends RuntimeException {
    public ProfileImportUnavailableException(String code, Throwable cause) {
      super(code, cause);
    }
  }
}
