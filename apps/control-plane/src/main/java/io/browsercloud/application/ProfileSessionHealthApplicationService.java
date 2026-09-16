package io.browsercloud.application;

import static io.browsercloud.api.ProfileSessionHealthModels.*;

import io.browsercloud.api.BusinessRecoveryModels.Verdict;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.persistence.ProfileJpaRepository;
import io.browsercloud.persistence.ProfileSiteSessionHealthEntity;
import io.browsercloud.persistence.ProfileSiteSessionHealthJpaRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Website authentication health derived only from fenced Business Recovery observations. */
@Service
public class ProfileSessionHealthApplicationService {
  private static final Duration HEALTH_FRESHNESS = Duration.ofMinutes(15);

  private final ProfileSiteSessionHealthJpaRepository repository;
  private final ProfileJpaRepository profiles;

  public ProfileSessionHealthApplicationService(
      ProfileSiteSessionHealthJpaRepository repository, ProfileJpaRepository profiles) {
    this.repository = repository;
    this.profiles = profiles;
  }

  @Transactional
  public void observe(
      String tenantId,
      String profileId,
      String sessionId,
      String applicationId,
      String pageUrl,
      long contextEpoch,
      long stateVersion,
      Verdict verdict,
      Instant now) {
    var state = persistedState(verdict);
    if (state == null) {
      return;
    }
    var origin = normalizedOrigin(pageUrl);
    if (origin == null) {
      return;
    }
    var profile =
        profiles
            .findForUpdate(profileId)
            .orElseThrow(() -> new ProfileApplicationService.ProfileNotFoundException(profileId));
    if (!tenantId.equals(profile.getTenantId())) {
      throw new TenantAccessDeniedException(profileId);
    }
    var existing = repository.findByTenantIdAndProfileIdAndSiteOrigin(tenantId, profileId, origin);
    var entity =
        existing.orElseGet(
            () ->
                new ProfileSiteSessionHealthEntity(
                    healthId(tenantId, profileId, origin), tenantId, profileId, origin, now));
    if (existing.isPresent()
        && sessionId.equals(entity.getSourceSessionId())
        && (entity.getContextEpoch() > contextEpoch
            || (entity.getContextEpoch() == contextEpoch
                && entity.getStateVersion() >= stateVersion))) {
      return;
    }
    entity.observe(
        applicationId,
        state,
        verdict.name(),
        sessionId,
        contextEpoch,
        stateVersion,
        now,
        now.plus(HEALTH_FRESHNESS));
    repository.save(entity);
  }

  @Transactional(readOnly = true)
  public ProfileSiteSessionHealthListResponse list(String tenantId, String profileId, Instant now) {
    var profile =
        profiles
            .findById(profileId)
            .orElseThrow(() -> new ProfileApplicationService.ProfileNotFoundException(profileId));
    if (!tenantId.equals(profile.getTenantId())) {
      throw new TenantAccessDeniedException(profileId);
    }
    var entities =
        repository.findAllByTenantIdAndProfileIdOrderByUpdatedAtDesc(tenantId, profileId);
    var items = entities.stream().map(item -> toView(item, now)).toList();
    return new ProfileSiteSessionHealthListResponse(summary(entities, now), items, items.size());
  }

  @Transactional(readOnly = true)
  public Map<String, ProfileSessionHealthSummary> summaries(String tenantId, Instant now) {
    return repository.findAllByTenantId(tenantId).stream()
        .collect(Collectors.groupingBy(ProfileSiteSessionHealthEntity::getProfileId))
        .entrySet()
        .stream()
        .collect(
            Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> summary(entry.getValue(), now)));
  }

  @Transactional(readOnly = true)
  public ProfileSessionHealthSummary summary(String tenantId, String profileId, Instant now) {
    return summary(
        repository.findAllByTenantIdAndProfileIdOrderByUpdatedAtDesc(tenantId, profileId), now);
  }

  public ProfileSessionHealthSummary notChecked() {
    return new ProfileSessionHealthSummary("NOT_CHECKED", 0, null, null);
  }

  private static ProfileSessionHealthSummary summary(
      List<ProfileSiteSessionHealthEntity> items, Instant now) {
    if (items.isEmpty()) {
      return new ProfileSessionHealthSummary("NOT_CHECKED", 0, null, null);
    }
    var state =
        items.stream().anyMatch(item -> "REAUTH_REQUIRED".equals(item.getHealthState()))
            ? "REAUTH_REQUIRED"
            : items.stream().anyMatch(item -> "DEGRADED".equals(effectiveState(item, now)))
                ? "DEGRADED"
                : items.stream().anyMatch(item -> "HEALTHY".equals(effectiveState(item, now)))
                    ? "HEALTHY"
                    : "STALE";
    var checkedAt =
        items.stream()
            .map(ProfileSiteSessionHealthEntity::getCheckedAt)
            .max(Comparator.naturalOrder())
            .orElse(null);
    var freshUntil =
        items.stream()
            .map(ProfileSiteSessionHealthEntity::getFreshUntil)
            .max(Comparator.naturalOrder())
            .orElse(null);
    return new ProfileSessionHealthSummary(state, items.size(), checkedAt, freshUntil);
  }

  private static ProfileSiteSessionHealthView toView(
      ProfileSiteSessionHealthEntity entity, Instant now) {
    return new ProfileSiteSessionHealthView(
        entity.getHealthId(),
        entity.getProfileId(),
        entity.getSiteOrigin(),
        entity.getApplicationId(),
        effectiveState(entity, now),
        entity.getReasonCode(),
        entity.getSourceSessionId(),
        entity.getContextEpoch(),
        entity.getStateVersion(),
        entity.getCheckedAt(),
        entity.getFreshUntil(),
        entity.getAuthenticatedAt(),
        entity.getReauthRequiredAt());
  }

  private static String effectiveState(ProfileSiteSessionHealthEntity entity, Instant now) {
    if (!"HEALTHY".equals(entity.getHealthState())) {
      return entity.getHealthState();
    }
    return now.isAfter(entity.getFreshUntil()) ? "STALE" : entity.getHealthState();
  }

  private static String persistedState(Verdict verdict) {
    return switch (verdict) {
      case READY, READY_WITH_WARNING -> "HEALTHY";
      case LOGIN_REQUIRED -> "REAUTH_REQUIRED";
      case PERMISSION_CHANGED, ACCOUNT_MISMATCH -> "DEGRADED";
      default -> null;
    };
  }

  private static String normalizedOrigin(String pageUrl) {
    try {
      var uri = URI.create(pageUrl);
      if (uri.getScheme() == null || uri.getHost() == null) {
        return null;
      }
      var scheme = uri.getScheme().toLowerCase();
      if (!"http".equals(scheme) && !"https".equals(scheme)) {
        return null;
      }
      var port = uri.getPort();
      var defaultPort =
          ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
      var host = uri.getHost().toLowerCase();
      if (host.contains(":")) {
        host = "[" + host + "]";
      }
      return scheme + "://" + host + (port < 0 || defaultPort ? "" : ":" + port);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static String healthId(String tenantId, String profileId, String origin) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var hash =
          digest.digest(
              (tenantId + "\u0000" + profileId + "\u0000" + origin)
                  .getBytes(StandardCharsets.UTF_8));
      return "psh_" + HexFormat.of().formatHex(hash, 0, 16);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }
}
