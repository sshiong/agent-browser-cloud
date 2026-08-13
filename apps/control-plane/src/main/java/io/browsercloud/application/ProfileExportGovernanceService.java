package io.browsercloud.application;

import static io.browsercloud.api.ProfileExportModels.*;
import static io.browsercloud.application.ProfileExportAccessNodeGateway.*;

import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.persistence.BrowserNodeEntity;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.persistence.ProfileEntity;
import io.browsercloud.persistence.ProfileJpaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileExportGovernanceService {

  private static final Duration GRANT_LIFETIME = Duration.ofMinutes(5);
  private static final Duration NODE_FRESHNESS = Duration.ofSeconds(60);
  private static final int SIGNED_URL_SECONDS = 60;

  private final ProfileJpaRepository profiles;
  private final BrowserNodeJpaRepository nodes;
  private final ProfileExportGovernanceStore store;
  private final ProfileExportAccessNodeGateway nodeAccess;
  private final AuditApplicationService audit;

  public ProfileExportGovernanceService(
      ProfileJpaRepository profiles,
      BrowserNodeJpaRepository nodes,
      ProfileExportGovernanceStore store,
      ProfileExportAccessNodeGateway nodeAccess,
      AuditApplicationService audit) {
    this.profiles = profiles;
    this.nodes = nodes;
    this.store = store;
    this.nodeAccess = nodeAccess;
    this.audit = audit;
  }

  @Transactional
  public ProfileExportGrantView createGrant(
      String profileId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      CreateProfileExportGrantRequest request) {
    var profile = requireProfile(profileId, tenantId);
    requireCheckpoint(profile);
    var existing = store.findByIdempotency(tenantId, actorId, idempotencyKey);
    if (existing.isPresent()) {
      requireSame(existing.orElseThrow(), profile, request.purpose());
      return existing.orElseThrow();
    }
    var now = Instant.now();
    var grantId = "pxg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    store.insertGrant(
        grantId,
        tenantId,
        profileId,
        profile.getLatestCheckpointId(),
        profile.getLatestCheckpointEpochOrZero(),
        actorId,
        request.purpose(),
        idempotencyKey,
        requestId,
        now.plus(GRANT_LIFETIME),
        now);
    var created =
        store
            .findByIdempotency(tenantId, actorId, idempotencyKey)
            .orElseThrow(() -> new ProfileExportRejectedException("PROFILE_EXPORT_STATE_MISSING"));
    requireSame(created, profile, request.purpose());
    appendAudit(
        tenantId,
        actorId,
        grantId,
        profileId,
        "PROFILE_EXPORT_ACCESS_GRANTED",
        "COMMITTED",
        request.purpose().name(),
        requestId);
    return created;
  }

  public RedeemProfileExportResponse redeem(
      String profileId, String grantId, String tenantId, String actorId, String requestId) {
    var profile = requireProfile(profileId, tenantId);
    var claim = store.claim(tenantId, profileId, grantId, actorId, Instant.now());
    if (!claim.checkpointId().equals(profile.getLatestCheckpointId())
        || claim.checkpointEpoch() != profile.getLatestCheckpointEpochOrZero()) {
      store.failGrant(grantId, "PROFILE_CHECKPOINT_CHANGED", Instant.now());
      throw new ProfileExportRejectedException("PROFILE_CHECKPOINT_CHANGED");
    }
    var candidates = nodes.findProfileExportCandidates(Instant.now().minus(NODE_FRESHNESS));
    if (candidates.isEmpty()) {
      store.failGrant(grantId, "PROFILE_EXPORT_NODE_UNAVAILABLE", Instant.now());
      throw new ProfileExportUnavailableException("PROFILE_EXPORT_NODE_UNAVAILABLE");
    }
    RuntimeException last = null;
    for (BrowserNodeEntity node : candidates) {
      try {
        var signed =
            nodeAccess.sign(
                new SignProfileExportRequest(
                    grantId,
                    node.getNodeId(),
                    tenantId,
                    profileId,
                    claim.checkpointId(),
                    SIGNED_URL_SECONDS));
        store.commitGrant(
            grantId,
            signed.nodeId(),
            signed.archiveSha256(),
            signed.archiveSizeBytes(),
            Instant.now());
        appendAudit(
            tenantId,
            actorId,
            grantId,
            profileId,
            "PROFILE_EXPORT_ACCESS_REDEEMED",
            "COMMITTED",
            "ONE_TIME_REDEEMED",
            requestId);
        return new RedeemProfileExportResponse(
            signed.grantId(),
            signed.profileId(),
            signed.checkpointId(),
            signed.archiveSha256(),
            signed.archiveSizeBytes(),
            signed.downloadUrl(),
            signed.expiresAt());
      } catch (ProfileExportNodeUnavailableException exception) {
        last = exception;
      } catch (ProfileExportNodeRejectedException exception) {
        store.failGrant(grantId, "PROFILE_EXPORT_ARCHIVE_INVALID", Instant.now());
        appendAudit(
            tenantId,
            actorId,
            grantId,
            profileId,
            "PROFILE_EXPORT_ACCESS_REDEEMED",
            "FAILED",
            "ARCHIVE_INVALID",
            requestId);
        throw exception;
      }
    }
    store.failGrant(grantId, "PROFILE_EXPORT_NODE_FAILED", Instant.now());
    appendAudit(
        tenantId,
        actorId,
        grantId,
        profileId,
        "PROFILE_EXPORT_ACCESS_REDEEMED",
        "FAILED",
        "NO_HEALTHY_SIGNER",
        requestId);
    throw new ProfileExportUnavailableException("PROFILE_EXPORT_NODE_FAILED", last);
  }

  private ProfileEntity requireProfile(String profileId, String tenantId) {
    var profile =
        profiles
            .findById(profileId)
            .orElseThrow(() -> new ProfileApplicationService.ProfileNotFoundException(profileId));
    if (!tenantId.equals(profile.getTenantId())) throw new TenantAccessDeniedException(profileId);
    return profile;
  }

  private static void requireCheckpoint(ProfileEntity profile) {
    if (profile.getLatestCheckpointId() == null
        || profile.getLatestCheckpointEpochOrZero() <= 0
        || profile.getCoreSizeBytes() <= 0
        || profile.getCheckpointFileCount() <= 0) {
      throw new ProfileExportRejectedException("PROFILE_CHECKPOINT_NOT_EXPORTABLE");
    }
  }

  private static void requireSame(
      ProfileExportGrantView existing, ProfileEntity profile, ProfileExportPurpose purpose) {
    if (!existing.profileId().equals(profile.getProfileId())
        || !existing.checkpointId().equals(profile.getLatestCheckpointId())
        || existing.checkpointEpoch() != profile.getLatestCheckpointEpochOrZero()
        || existing.purpose() != purpose) {
      throw new ProfileExportRejectedException("PROFILE_EXPORT_IDEMPOTENCY_CONFLICT");
    }
  }

  private void appendAudit(
      String tenantId,
      String actorId,
      String grantId,
      String profileId,
      String action,
      String result,
      String reason,
      String requestId) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            null,
            "PROFILE_EXPORT",
            "USER",
            actorId,
            "PROFILE",
            profileId,
            action,
            result,
            Map.of("grantId", grantId, "reason", reason),
            requestId));
  }

  public static final class ProfileExportRejectedException extends RuntimeException {
    public ProfileExportRejectedException(String reason) {
      super(reason);
    }
  }

  public static final class ProfileExportUnavailableException extends RuntimeException {
    public ProfileExportUnavailableException(String reason) {
      super(reason);
    }

    public ProfileExportUnavailableException(String reason, Throwable cause) {
      super(reason, cause);
    }
  }
}
