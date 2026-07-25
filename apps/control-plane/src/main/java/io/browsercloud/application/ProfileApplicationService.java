package io.browsercloud.application;

import io.browsercloud.api.CreateProfileRequest;
import io.browsercloud.api.ProfileListResponse;
import io.browsercloud.api.ProfileView;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.persistence.ProfileEntity;
import io.browsercloud.persistence.ProfileJpaRepository;
import io.browsercloud.persistence.SessionJpaRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile 控制面：租户隔离、生命周期元数据与最新检查点索引。 */
@Service
public class ProfileApplicationService {

  private final ProfileJpaRepository repository;
  private final SessionJpaRepository sessionRepository;

  public ProfileApplicationService(
      ProfileJpaRepository repository, SessionJpaRepository sessionRepository) {
    this.repository = repository;
    this.sessionRepository = sessionRepository;
  }

  @Transactional
  public ProfileView create(String tenantId, CreateProfileRequest request) {
    if (repository.existsById(request.profileId())) {
      throw new ProfileAlreadyExistsException(request.profileId());
    }
    var now = Instant.now();
    var entity =
        new ProfileEntity(
            request.profileId(),
            tenantId,
            request.name(),
            request.description(),
            storagePath(tenantId, request.profileId()),
            now);
    return toView(repository.save(entity));
  }

  @Transactional
  public void ensureExists(String tenantId, String profileId) {
    var existing = repository.findById(profileId);
    if (existing.isPresent()) {
      requireTenant(existing.get(), tenantId);
      return;
    }
    var now = Instant.now();
    repository.save(
        new ProfileEntity(
            profileId, tenantId, profileId, null, storagePath(tenantId, profileId), now));
  }

  @Transactional(readOnly = true)
  public ProfileView get(String tenantId, String profileId) {
    var profile =
        repository.findById(profileId).orElseThrow(() -> new ProfileNotFoundException(profileId));
    requireTenant(profile, tenantId);
    return toView(profile);
  }

  @Transactional(readOnly = true)
  public ProfileListResponse list(String tenantId) {
    var items =
        repository.findAllByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
            .map(ProfileApplicationService::toView)
            .toList();
    return new ProfileListResponse(items, items.size());
  }

  @Transactional
  public void recordCheckpoint(String tenantId, NodeEvent.RuntimeStopped event) {
    var session =
        sessionRepository
            .findById(event.sessionId())
            .orElseThrow(() -> new IllegalArgumentException("checkpoint session does not exist"));
    if (!session.getTenantId().equals(tenantId)
        || !session.getProfileId().equals(event.profileId())) {
      throw new TenantAccessDeniedException(event.sessionId());
    }
    var profile =
        repository
            .findById(event.profileId())
            .orElseThrow(() -> new ProfileNotFoundException(event.profileId()));
    requireTenant(profile, tenantId);
    if (event.checkpointEpoch() <= profile.getLatestCheckpointEpochOrZero()) {
      return;
    }
    profile.commitCheckpoint(
        event.checkpointId(),
        event.checkpointEpoch(),
        event.profileWriteEpoch(),
        event.coreSizeBytes(),
        event.checkpointFileCount(),
        event.restoreStatus(),
        Instant.now());
    repository.save(profile);
  }

  private static void requireTenant(ProfileEntity profile, String tenantId) {
    if (!profile.getTenantId().equals(tenantId)) {
      throw new TenantAccessDeniedException(profile.getProfileId());
    }
  }

  private static String storagePath(String tenantId, String profileId) {
    return "tenants/" + tenantId + "/profiles/" + profileId;
  }

  private static ProfileView toView(ProfileEntity profile) {
    return new ProfileView(
        profile.getProfileId(),
        profile.getTenantId(),
        profile.getName(),
        profile.getDescription(),
        profile.getLatestCheckpointId(),
        profile.getLatestCheckpointEpoch(),
        profile.getProfileWriteEpoch(),
        profile.getCoreSizeBytes(),
        profile.getCheckpointFileCount(),
        profile.getRestoreStatus(),
        profile.getState(),
        profile.getCreatedAt(),
        profile.getUpdatedAt(),
        profile.getLastCheckpointAt());
  }

  public static final class ProfileAlreadyExistsException extends RuntimeException {
    public ProfileAlreadyExistsException(String profileId) {
      super("Profile already exists: " + profileId);
    }
  }

  public static final class ProfileNotFoundException extends RuntimeException {
    public ProfileNotFoundException(String profileId) {
      super("Profile not found: " + profileId);
    }
  }
}
