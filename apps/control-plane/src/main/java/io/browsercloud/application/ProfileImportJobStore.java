package io.browsercloud.application;

import static io.browsercloud.api.ProfileImportModels.ProfileImportView;

import io.browsercloud.api.ProfileImportModels.ProfileImportListResponse;
import io.browsercloud.application.ProfileImportNodeGateway.ProfileImportNodeResult;
import io.browsercloud.persistence.ProfileEntity;
import io.browsercloud.persistence.ProfileImportJobEntity;
import io.browsercloud.persistence.ProfileImportJobJpaRepository;
import io.browsercloud.persistence.ProfileJpaRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions around a long-running, external Profile archive stream. */
@Service
public class ProfileImportJobStore {

  private static final Duration ABANDONED_UPLOAD_AFTER = Duration.ofMinutes(5);

  private final ProfileImportJobJpaRepository jobs;
  private final ProfileJpaRepository profiles;
  private final AuditApplicationService audit;
  private final JdbcTemplate jdbc;

  public ProfileImportJobStore(
      ProfileImportJobJpaRepository jobs,
      ProfileJpaRepository profiles,
      AuditApplicationService audit,
      JdbcTemplate jdbc) {
    this.jobs = jobs;
    this.profiles = profiles;
    this.audit = audit;
    this.jdbc = jdbc;
  }

  @Transactional
  public ProfileImportJobEntity claim(ProfileImportClaim claim) {
    var now = Instant.now();
    jdbc.update(
        """
        INSERT INTO profile_import_jobs (
            import_id, tenant_id, owner_actor_id, idempotency_key, request_hash, request_id,
            operation_id, profile_id, profile_name, profile_description, runtime_build_id,
            archive_sha256, archive_size_bytes, state, node_id, checkpoint_id, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', NULL, ?, ?, ?)
        ON CONFLICT (tenant_id, owner_actor_id, idempotency_key) DO NOTHING
        """,
        claim.importId(),
        claim.tenantId(),
        claim.actorId(),
        claim.idempotencyKey(),
        claim.requestHash(),
        claim.requestId(),
        claim.operationId(),
        claim.profileId(),
        claim.profileName(),
        claim.profileDescription(),
        claim.runtimeBuildId(),
        claim.archiveSha256(),
        claim.archiveSizeBytes(),
        claim.checkpointId(),
        Timestamp.from(now),
        Timestamp.from(now));
    var job =
        jobs.findOwnedIdempotencyForUpdate(
                claim.tenantId(), claim.actorId(), claim.idempotencyKey())
            .orElseThrow();
    if (!job.getRequestHash().equals(claim.requestHash())) {
      throw new ProfileImportConflictException("PROFILE_IMPORT_IDEMPOTENCY_CONFLICT");
    }
    if (!"COMMITTED".equals(job.getState()) && profiles.existsById(job.getProfileId())) {
      throw new ProfileImportConflictException("PROFILE_ID_UNAVAILABLE");
    }
    return job;
  }

  @Transactional
  public ProfileImportJobEntity begin(String importId, String tenantId, String actorId) {
    var job = requireForUpdate(importId, tenantId, actorId);
    if ("COMMITTED".equals(job.getState())) return job;
    if (("UPLOADING".equals(job.getState()) || "VALIDATING".equals(job.getState()))
        && job.getUpdatedAt().isAfter(Instant.now().minus(ABANDONED_UPLOAD_AFTER))) {
      throw new ProfileImportConflictException("PROFILE_IMPORT_ALREADY_RUNNING");
    }
    job.uploading(null, Instant.now());
    return jobs.save(job);
  }

  @Transactional
  public void validating(String importId, String tenantId, String actorId) {
    var job = requireForUpdate(importId, tenantId, actorId);
    if ("COMMITTED".equals(job.getState())) return;
    job.validating(Instant.now());
    jobs.save(job);
  }

  @Transactional
  public ProfileImportView commit(
      String importId, String tenantId, String actorId, ProfileImportNodeResult result) {
    var job = requireForUpdate(importId, tenantId, actorId);
    if ("COMMITTED".equals(job.getState())) return toView(job);
    if (!job.getImportId().equals(result.importId())
        || !job.getProfileId().equals(result.profileId())
        || !job.getCheckpointId().equals(result.checkpointId())
        || !job.getArchiveSha256().equalsIgnoreCase(result.archiveSha256())
        || job.getArchiveSizeBytes() != result.archiveSizeBytes()) {
      throw new ProfileImportConflictException("PROFILE_IMPORT_RESULT_MISMATCH");
    }
    if (profiles.existsById(job.getProfileId())) {
      throw new ProfileImportConflictException("PROFILE_ID_UNAVAILABLE");
    }
    var now = Instant.now();
    var profile =
        new ProfileEntity(
            job.getProfileId(),
            job.getTenantId(),
            job.getProfileName(),
            job.getProfileDescription(),
            "tenants/" + job.getTenantId() + "/profiles/" + job.getProfileId(),
            now);
    profile.commitCheckpoint(
        result.checkpointId(),
        result.checkpointEpoch(),
        result.profileWriteEpoch(),
        result.coreSizeBytes(),
        result.checkpointFileCount(),
        "TECHNICAL_READY",
        now);
    try {
      profiles.saveAndFlush(profile);
    } catch (DataIntegrityViolationException exception) {
      throw new ProfileImportConflictException("PROFILE_ID_UNAVAILABLE");
    }
    job.committed(
        result.nodeId(),
        result.checkpointEpoch(),
        result.profileWriteEpoch(),
        result.coreSizeBytes(),
        result.checkpointFileCount(),
        now);
    jobs.save(job);
    audit.append(
        new AuditApplicationService.AuditRecord(
            job.getTenantId(),
            null,
            "PROFILE_IMPORT",
            "USER",
            job.getOwnerActorId(),
            "PROFILE",
            job.getProfileId(),
            "PROFILE_CHECKPOINT_IMPORTED",
            "COMMITTED",
            Map.of(
                "importId",
                job.getImportId(),
                "operationId",
                job.getOperationId(),
                "nodeId",
                result.nodeId(),
                "checkpointId",
                result.checkpointId(),
                "archiveSha256",
                result.archiveSha256(),
                "archiveSizeBytes",
                result.archiveSizeBytes(),
                "coreSizeBytes",
                result.coreSizeBytes(),
                "checkpointFileCount",
                result.checkpointFileCount()),
            job.getRequestId()));
    return toView(job);
  }

  @Transactional
  public void fail(String importId, String tenantId, String actorId, String errorCode) {
    var job = requireForUpdate(importId, tenantId, actorId);
    if ("COMMITTED".equals(job.getState())) return;
    job.failed(errorCode, Instant.now());
    jobs.save(job);
    audit.append(
        new AuditApplicationService.AuditRecord(
            job.getTenantId(),
            null,
            "PROFILE_IMPORT",
            "USER",
            job.getOwnerActorId(),
            "PROFILE",
            job.getProfileId(),
            "PROFILE_CHECKPOINT_IMPORT_FAILED",
            errorCode,
            Map.of(
                "importId",
                job.getImportId(),
                "operationId",
                job.getOperationId(),
                "archiveSha256",
                job.getArchiveSha256(),
                "archiveSizeBytes",
                job.getArchiveSizeBytes()),
            job.getRequestId()));
  }

  @Transactional(readOnly = true)
  public ProfileImportView get(String importId, String tenantId, String actorId) {
    return toView(
        jobs.findByImportIdAndTenantIdAndOwnerActorId(importId, tenantId, actorId)
            .orElseThrow(ProfileImportNotFoundException::new));
  }

  @Transactional(readOnly = true)
  public ProfileImportListResponse list(String tenantId, String actorId, int limit) {
    var items =
        jobs
            .findAllByTenantIdAndOwnerActorIdOrderByCreatedAtDesc(
                tenantId, actorId, PageRequest.of(0, Math.max(1, Math.min(limit, 50))))
            .stream()
            .map(ProfileImportJobStore::toView)
            .toList();
    return new ProfileImportListResponse(
        items, Math.toIntExact(jobs.countByTenantIdAndOwnerActorId(tenantId, actorId)));
  }

  private ProfileImportJobEntity requireForUpdate(
      String importId, String tenantId, String actorId) {
    var job = jobs.findByIdForUpdate(importId).orElseThrow(ProfileImportNotFoundException::new);
    if (!tenantId.equals(job.getTenantId()) || !actorId.equals(job.getOwnerActorId())) {
      throw new ProfileImportNotFoundException();
    }
    return job;
  }

  static ProfileImportView toView(ProfileImportJobEntity job) {
    return new ProfileImportView(
        job.getImportId(),
        job.getOperationId(),
        job.getProfileId(),
        job.getProfileName(),
        job.getRuntimeBuildId(),
        job.getArchiveSha256(),
        job.getArchiveSizeBytes(),
        job.getState(),
        job.getNodeId(),
        job.getCheckpointId(),
        job.getCheckpointEpoch(),
        job.getProfileWriteEpoch(),
        job.getCoreSizeBytes(),
        job.getCheckpointFileCount(),
        job.getErrorCode(),
        job.getRequestId(),
        job.getCreatedAt(),
        job.getUpdatedAt(),
        job.getCompletedAt());
  }

  public record ProfileImportClaim(
      String importId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestHash,
      String requestId,
      String operationId,
      String profileId,
      String profileName,
      String profileDescription,
      String runtimeBuildId,
      String archiveSha256,
      long archiveSizeBytes,
      String checkpointId) {}

  public static final class ProfileImportConflictException extends RuntimeException {
    public ProfileImportConflictException(String code) {
      super(code);
    }
  }

  public static final class ProfileImportNotFoundException extends RuntimeException {}
}
