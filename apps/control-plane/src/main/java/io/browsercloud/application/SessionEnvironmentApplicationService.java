package io.browsercloud.application;

import static io.browsercloud.api.EnvironmentImportModels.*;
import static io.browsercloud.api.SessionEnvironmentModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.CreateSessionRequest;
import io.browsercloud.api.ResourcePolicyRequest;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.persistence.SessionApplicationBindingJpaRepository;
import io.browsercloud.persistence.SessionJpaRepository;
import io.browsercloud.persistence.SessionProxyBindingAssignmentJpaRepository;
import io.browsercloud.persistence.SessionResourceDemandJpaRepository;
import io.browsercloud.persistence.SessionResourcePolicyJpaRepository;
import io.browsercloud.persistence.SessionTagAssignmentJpaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds portable environment manifests and creates fenced configuration clones. */
@Service
public class SessionEnvironmentApplicationService {

  private static final List<String> EXCLUDED_DATA =
      List.of(
          "browserRuntimeState",
          "cookiesAndSiteStorage",
          "profileCheckpointBytes",
          "proxyCredentials",
          "agentSecrets",
          "recordingsAndScreenshots",
          "operationsAndExecutionHistory");

  private final SessionRepository sessions;
  private final SessionJpaRepository sessionEntities;
  private final SessionResourcePolicyJpaRepository policies;
  private final SessionResourceDemandJpaRepository demands;
  private final SessionTagAssignmentJpaRepository tags;
  private final SessionApplicationBindingJpaRepository applicationBindings;
  private final SessionProxyBindingAssignmentJpaRepository proxyAssignments;
  private final SessionIdentityApplicationService identities;
  private final ProfileApplicationService profiles;
  private final SessionApplicationService sessionApplicationService;
  private final AuditApplicationService audit;
  private final ObjectMapper mapper;

  public SessionEnvironmentApplicationService(
      SessionRepository sessions,
      SessionJpaRepository sessionEntities,
      SessionResourcePolicyJpaRepository policies,
      SessionResourceDemandJpaRepository demands,
      SessionTagAssignmentJpaRepository tags,
      SessionApplicationBindingJpaRepository applicationBindings,
      SessionProxyBindingAssignmentJpaRepository proxyAssignments,
      SessionIdentityApplicationService identities,
      ProfileApplicationService profiles,
      SessionApplicationService sessionApplicationService,
      AuditApplicationService audit,
      ObjectMapper mapper) {
    this.sessions = sessions;
    this.sessionEntities = sessionEntities;
    this.policies = policies;
    this.demands = demands;
    this.tags = tags;
    this.applicationBindings = applicationBindings;
    this.proxyAssignments = proxyAssignments;
    this.identities = identities;
    this.profiles = profiles;
    this.sessionApplicationService = sessionApplicationService;
    this.audit = audit;
    this.mapper = mapper;
  }

  @Transactional
  public EnvironmentConfigurationExportView exportConfiguration(
      String sessionId, String tenantId, String actorId, String requestId) {
    var specification = snapshot(sessionId, tenantId);
    var manifest =
        new PreviewEnvironmentImportRequest(
            1, "Environment configuration export", List.of(specification));
    var now = Instant.now();
    var hash = sha256(write(manifest));
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "SESSION_CONFIGURATION",
            "USER",
            actorId,
            "SESSION",
            sessionId,
            "EXPORT_CONFIGURATION",
            "COMMITTED",
            Map.of("manifestHash", hash, "schemaVersion", 1, "excludedData", EXCLUDED_DATA),
            requestId));
    return new EnvironmentConfigurationExportView(sessionId, now, hash, manifest, EXCLUDED_DATA);
  }

  @Transactional
  public CloneEnvironmentResponse cloneConfiguration(
      String sessionId,
      String tenantId,
      String actorId,
      boolean platformAdmin,
      String idempotencyKey,
      String requestId,
      CloneEnvironmentRequest request) {
    var source = snapshot(sessionId, tenantId);
    var targetProfileId =
        request.profileMode() == CloneProfileMode.REUSE_SOURCE_PROFILE
            ? source.profileId()
            : cloneProfileId(tenantId, sessionId, idempotencyKey);
    var metadata = new LinkedHashMap<String, String>();
    metadata.put("displayName", request.displayName().strip());
    metadata.put("clonedFromSessionId", sessionId);
    metadata.put("cloneProfileMode", request.profileMode().name());
    if (request.profileMode() == CloneProfileMode.NEW_EMPTY_PROFILE) {
      profiles.ensureExists(
          tenantId,
          targetProfileId,
          request.displayName().strip() + " Profile",
          "Empty Profile created for a configuration clone of " + sessionId);
    }
    var createRequest = toCreateRequest(tenantId, source, targetProfileId, metadata);
    var created =
        sessionApplicationService.create(
            createRequest, idempotencyKey, actorId, requestId, platformAdmin);
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "SESSION_CONFIGURATION",
            "USER",
            actorId,
            "SESSION",
            sessionId,
            "CLONE_CONFIGURATION",
            "COMMITTED",
            Map.of(
                "targetSessionId",
                created.sessionId(),
                "profileMode",
                request.profileMode().name(),
                "targetProfileId",
                targetProfileId),
            requestId));
    return new CloneEnvironmentResponse(sessionId, request.profileMode(), targetProfileId, created);
  }

  @Transactional(readOnly = true)
  EnvironmentImportSpec snapshot(String sessionId, String tenantId) {
    var descriptor = sessions.describe(sessionId);
    var context = descriptor.context();
    if (!tenantId.equals(context.tenantId())) {
      throw new TenantAccessDeniedException(sessionId);
    }
    var entity = sessionEntities.findById(sessionId).orElseThrow();
    var policy = policies.findBySessionIdAndTenantId(sessionId, tenantId).orElseThrow();
    var demand = demands.findById(sessionId).orElseThrow();
    var tagIds =
        tags.findAllByTenantIdAndSessionIdOrderByAssignedAtAsc(tenantId, sessionId).stream()
            .map(item -> item.getTagId())
            .toList();
    var applicationId =
        applicationBindings
            .findBySessionIdAndTenantId(sessionId, tenantId)
            .map(item -> item.getApplicationId())
            .orElse(null);
    var proxyBindingProfileId =
        proxyAssignments
            .findBySessionIdAndTenantId(sessionId, tenantId)
            .map(item -> item.getBindingProfileId())
            .orElse(null);
    var metadata = readMetadata(entity.getMetadata());
    return new EnvironmentImportSpec(
        descriptor.displayName(),
        boundedDescription(metadata.get("description")),
        context.profileId(),
        context.runtimeBuildId(),
        applicationId,
        descriptor.groupId(),
        tagIds,
        descriptor.region(),
        proxyBindingProfileId,
        new ResourcePolicyRequest(
            policy.mode(),
            policy.onMaximumReached(),
            policy.isAllowMigration(),
            policy.isAllowHibernate(),
            policy.isBlockMigrationDuringHumanTakeover(),
            policy.executionEnvironment(),
            policy.getMinimumTemplate(),
            policy.getMaximumCpuMillis(),
            policy.getMaximumMemoryMib(),
            policy.getMaximumCostPerHour(),
            policy.getScaleUpWindowSeconds(),
            policy.getScaleDownWindowSeconds(),
            policy.getAdjustmentCooldownSeconds()),
        demand.getRequestedTabs(),
        demand.getAgentActionsPerMinute(),
        demand.isRemoteDesktop(),
        descriptor.humanTakeoverEnabled(),
        descriptor.agentPolicy(),
        demand.isWeb3Workload(),
        demand.isMediaWorkload(),
        demand.getRequestedMediaStreams(),
        demand.getMediaBitrateKbps(),
        demand.isVideoRecordingRequested(),
        descriptor.extensionIds(),
        identities.get(sessionId, tenantId).spec());
  }

  private static CreateSessionRequest toCreateRequest(
      String tenantId,
      EnvironmentImportSpec source,
      String profileId,
      Map<String, String> metadata) {
    return new CreateSessionRequest(
        tenantId,
        profileId,
        source.runtimeBuildId(),
        source.applicationId(),
        source.groupId(),
        source.tagIds(),
        source.region(),
        source.proxyBindingProfileId(),
        null,
        source.resourcePolicy(),
        null,
        source.requestedTabs(),
        source.agentActionsPerMinute(),
        source.remoteDesktop(),
        source.humanTakeoverEnabled(),
        source.agentPolicy(),
        source.web3Workload(),
        source.mediaWorkload(),
        source.requestedMediaStreams(),
        source.mediaBitrateKbps(),
        source.videoRecording(),
        source.extensionIds(),
        metadata,
        source.identitySpec());
  }

  private Map<String, String> readMetadata(String value) {
    if (value == null || value.isBlank()) return Map.of();
    try {
      return mapper.readValue(value, new TypeReference<Map<String, String>>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Persisted Session metadata is invalid", exception);
    }
  }

  private byte[] write(Object value) {
    try {
      return mapper.writeValueAsBytes(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Environment configuration serialization failed", exception);
    }
  }

  private static String cloneProfileId(String tenantId, String sessionId, String idempotencyKey) {
    return "profile_clone_"
        + sha256(
                (tenantId + "\n" + sessionId + "\n" + idempotencyKey)
                    .getBytes(StandardCharsets.UTF_8))
            .substring(0, 20);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String boundedDescription(String value) {
    var normalized = blankToNull(value);
    return normalized == null || normalized.length() <= 512
        ? normalized
        : normalized.substring(0, 512);
  }
}
