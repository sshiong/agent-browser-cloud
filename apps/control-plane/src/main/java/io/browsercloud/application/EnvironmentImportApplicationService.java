package io.browsercloud.application;

import static io.browsercloud.api.EnvironmentImportModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.CreateSessionRequest;
import io.browsercloud.api.ResourcePolicyRequest;
import io.browsercloud.application.ApplicationBusinessRecoveryService.RecoveryContractApprovalRequiredException;
import io.browsercloud.application.ApplicationBusinessRecoveryService.RecoveryContractNotFoundException;
import io.browsercloud.application.ApplicationBusinessRecoveryService.RecoveryContractRejectedException;
import io.browsercloud.application.RuntimeBuildPolicy.RuntimeBuildRejectedException;
import io.browsercloud.application.WorkspaceGroupApplicationService.WorkspaceGroupNotFoundException;
import io.browsercloud.application.WorkspaceTagApplicationService.WorkspaceTagNotFoundException;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.resource.MaximumReachedPolicy;
import io.browsercloud.domain.resource.ResourcePolicyMode;
import io.browsercloud.persistence.EnvironmentImportItemEntity;
import io.browsercloud.persistence.EnvironmentImportItemJpaRepository;
import io.browsercloud.persistence.EnvironmentImportJobEntity;
import io.browsercloud.persistence.EnvironmentImportJobJpaRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL-authoritative Environment Import.
 *
 * <p>Preview validates references without creating Profiles, Sessions or Operations. Commit creates
 * every Session in one transaction, so callers never observe a partially imported manifest.
 */
@Service
public class EnvironmentImportApplicationService {

  private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

  private final EnvironmentImportJobJpaRepository jobs;
  private final EnvironmentImportItemJpaRepository items;
  private final IdempotencyService idempotency;
  private final ObjectMapper mapper;
  private final ProfileApplicationService profiles;
  private final RuntimeBuildPolicy runtimes;
  private final WorkspaceGroupApplicationService groups;
  private final WorkspaceTagApplicationService tags;
  private final WorkspaceSettingsApplicationService settings;
  private final ApplicationBusinessRecoveryService recovery;
  private final SessionApplicationService sessions;
  private final AuditApplicationService audit;

  public EnvironmentImportApplicationService(
      EnvironmentImportJobJpaRepository jobs,
      EnvironmentImportItemJpaRepository items,
      IdempotencyService idempotency,
      ObjectMapper mapper,
      ProfileApplicationService profiles,
      RuntimeBuildPolicy runtimes,
      WorkspaceGroupApplicationService groups,
      WorkspaceTagApplicationService tags,
      WorkspaceSettingsApplicationService settings,
      ApplicationBusinessRecoveryService recovery,
      SessionApplicationService sessions,
      AuditApplicationService audit) {
    this.jobs = jobs;
    this.items = items;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.profiles = profiles;
    this.runtimes = runtimes;
    this.groups = groups;
    this.tags = tags;
    this.settings = settings;
    this.recovery = recovery;
    this.sessions = sessions;
    this.audit = audit;
  }

  @Transactional
  public EnvironmentImportView preview(
      String tenantId,
      String actorId,
      boolean platformAdmin,
      String idempotencyKey,
      String requestId,
      PreviewEnvironmentImportRequest request) {
    var candidateImportId = newId("imp_");
    var importId =
        idempotency.claimEnvironmentImportPreview(
            tenantId, actorId, idempotencyKey, request, candidateImportId);
    if (!candidateImportId.equals(importId)) {
      return get(tenantId, actorId, importId);
    }

    var workspaceDefaults = settings.resolve(tenantId);
    var now = Instant.now();
    var seenHashes = new HashSet<String>();
    var prepared = new ArrayList<PreparedItem>(request.environments().size());
    var readyCount = 0;
    for (int index = 0; index < request.environments().size(); index++) {
      var specification = request.environments().get(index);
      var payload = mapper.convertValue(specification, PAYLOAD_TYPE);
      var requestHash = sha256(write(specification));
      var errors =
          validate(
              tenantId, platformAdmin, workspaceDefaults.defaultRuntimeBuildId(), specification);
      if (!seenHashes.add(requestHash)) {
        errors.add("DUPLICATE_ENVIRONMENT_SPEC");
      }
      if (errors.isEmpty()) {
        readyCount++;
      }
      prepared.add(
          new PreparedItem(
              newId("imi_"), index, specification, payload, requestHash, List.copyOf(errors)));
    }

    var state =
        readyCount == request.environments().size()
            ? EnvironmentImportState.VALIDATED
            : EnvironmentImportState.INVALID;
    var job =
        jobs.saveAndFlush(
            new EnvironmentImportJobEntity(
                importId,
                tenantId,
                actorId,
                request.name(),
                request.schemaVersion(),
                sha256(write(request)),
                state,
                request.environments().size(),
                readyCount,
                now));
    items.saveAll(
        prepared.stream()
            .map(
                item ->
                    new EnvironmentImportItemEntity(
                        item.itemId(),
                        importId,
                        tenantId,
                        item.index(),
                        item.specification().displayName(),
                        item.payload(),
                        item.requestHash(),
                        item.errors(),
                        now))
            .toList());
    items.flush();
    appendAudit(
        tenantId,
        actorId,
        importId,
        "ENVIRONMENT_IMPORT_PREVIEWED",
        state.name(),
        requestId,
        Map.of(
            "manifestHash",
            job.getManifestHash(),
            "totalCount",
            job.getTotalCount(),
            "readyCount",
            job.getReadyCount()));
    return toView(job, items.findAllByImportIdAndTenantIdOrderByItemIndexAsc(importId, tenantId));
  }

  @Transactional
  public EnvironmentImportView commit(
      String tenantId,
      String actorId,
      boolean platformAdmin,
      String importId,
      String idempotencyKey,
      String requestId,
      CommitEnvironmentImportRequest request) {
    var candidateMutationId = newId("mut_");
    var mutationId =
        idempotency.claimEnvironmentImportCommit(
            tenantId, actorId, importId, idempotencyKey, request, candidateMutationId);
    if (!candidateMutationId.equals(mutationId)) {
      return get(tenantId, actorId, importId);
    }
    var job =
        jobs.findOwnedForUpdate(importId, tenantId, actorId)
            .orElseThrow(EnvironmentImportNotFoundException::new);
    if (job.getVersion() != request.expectedVersion()) {
      throw new EnvironmentImportRejectedException("IMPORT_VERSION_MISMATCH");
    }
    if (job.getState() != EnvironmentImportState.VALIDATED) {
      throw new EnvironmentImportRejectedException("IMPORT_NOT_VALIDATED");
    }

    var importedItems = items.findAllByImportIdAndTenantIdOrderByItemIndexAsc(importId, tenantId);
    if (importedItems.size() != job.getTotalCount()
        || importedItems.stream()
            .anyMatch(
                item ->
                    item.getValidationState() != EnvironmentImportValidationState.READY
                        || item.getExecutionState() != EnvironmentImportExecutionState.PENDING)) {
      throw new EnvironmentImportRejectedException("IMPORT_LEDGER_INCONSISTENT");
    }

    var now = Instant.now();
    job.start(now);
    jobs.saveAndFlush(job);
    for (var item : importedItems) {
      var specification =
          mapper.convertValue(item.getRequestPayload(), EnvironmentImportSpec.class);
      var itemRequestId = requestId + ":" + item.getItemIndex();
      var response =
          sessions.create(
              toCreateRequest(tenantId, importId, specification),
              "environment-import-" + importId + "-" + item.getItemIndex(),
              actorId,
              itemRequestId,
              platformAdmin);
      item.succeed(response.sessionId(), response.operationId(), itemRequestId, Instant.now());
    }
    items.saveAll(importedItems);
    job.commit(importedItems.size(), Instant.now());
    jobs.saveAndFlush(job);
    appendAudit(
        tenantId,
        actorId,
        importId,
        "ENVIRONMENT_IMPORT_COMMITTED",
        "COMMITTED",
        requestId,
        Map.of(
            "manifestHash", job.getManifestHash(),
            "succeededCount", importedItems.size()));
    return toView(job, importedItems);
  }

  @Transactional(readOnly = true)
  public EnvironmentImportView get(String tenantId, String actorId, String importId) {
    var job =
        jobs.findByImportIdAndTenantIdAndOwnerActorId(importId, tenantId, actorId)
            .orElseThrow(EnvironmentImportNotFoundException::new);
    return toView(job, items.findAllByImportIdAndTenantIdOrderByItemIndexAsc(importId, tenantId));
  }

  @Transactional(readOnly = true)
  public EnvironmentImportListResponse list(String tenantId, String actorId) {
    var result =
        jobs.findTop20ByTenantIdAndOwnerActorIdOrderByCreatedAtDesc(tenantId, actorId).stream()
            .map(this::toListItem)
            .toList();
    return new EnvironmentImportListResponse(result, result.size());
  }

  private List<String> validate(
      String tenantId,
      boolean platformAdmin,
      String defaultRuntimeBuildId,
      EnvironmentImportSpec specification) {
    var errors = new ArrayList<String>();
    try {
      profiles.validateImportReference(tenantId, specification.profileId());
    } catch (TenantAccessDeniedException exception) {
      errors.add("PROFILE_TENANT_CONFLICT");
    }
    try {
      runtimes.requireApproved(
          specification.runtimeBuildId() == null
              ? defaultRuntimeBuildId
              : specification.runtimeBuildId());
    } catch (RuntimeBuildRejectedException exception) {
      errors.add("RUNTIME_" + exception.getMessage());
    }
    try {
      groups.requireExists(tenantId, specification.groupId());
    } catch (WorkspaceGroupNotFoundException exception) {
      errors.add("WORKSPACE_GROUP_NOT_FOUND");
    }
    try {
      tags.requireAllExist(tenantId, specification.tagIds());
    } catch (WorkspaceTagNotFoundException exception) {
      errors.add("WORKSPACE_TAG_NOT_FOUND");
    }
    try {
      recovery.validateBinding(tenantId, specification.applicationId());
    } catch (RecoveryContractNotFoundException exception) {
      errors.add("RECOVERY_CONTRACT_NOT_FOUND");
    } catch (RecoveryContractApprovalRequiredException exception) {
      errors.add("RECOVERY_CONTRACT_NOT_APPROVED");
    } catch (RecoveryContractRejectedException exception) {
      errors.add("RECOVERY_CONTRACT_INVALID");
    }
    ResourcePolicyRequest effectivePolicy = null;
    try {
      effectivePolicy =
          groups.resolvePolicy(tenantId, specification.groupId(), specification.resourcePolicy());
    } catch (WorkspaceGroupNotFoundException ignored) {
      // The reference error was already recorded above.
    }
    if (effectivePolicy != null && effectivePolicy.mode() != ResourcePolicyMode.AUTO) {
      errors.add("RESOURCE_POLICY_MODE_MUST_BE_AUTO");
    }
    if (effectivePolicy != null
        && effectivePolicy.onMaximumReached() == MaximumReachedPolicy.TERMINATE_STRICT
        && !platformAdmin) {
      errors.add("STRICT_TERMINATION_REQUIRES_PLATFORM_ADMIN");
    }
    if (!specification.mediaWorkload()
        && (specification.requestedMediaStreams() > 0
            || specification.mediaBitrateKbps() > 0
            || specification.videoRecording())) {
      errors.add("MEDIA_CAPACITY_REQUIRES_MEDIA_WORKLOAD");
    }
    return errors;
  }

  private static CreateSessionRequest toCreateRequest(
      String tenantId, String importId, EnvironmentImportSpec specification) {
    var metadata = new LinkedHashMap<String, String>();
    metadata.put("displayName", specification.displayName().strip());
    metadata.put("environmentImportId", importId);
    if (specification.description() != null && !specification.description().isBlank()) {
      metadata.put("description", specification.description().strip());
    }
    return new CreateSessionRequest(
        tenantId,
        specification.profileId(),
        specification.runtimeBuildId(),
        specification.applicationId(),
        specification.groupId(),
        specification.tagIds(),
        specification.region(),
        null,
        specification.resourcePolicy() == null
            ? new ResourcePolicyRequest(
                ResourcePolicyMode.AUTO,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null)
            : specification.resourcePolicy(),
        null,
        specification.requestedTabs(),
        specification.agentActionsPerMinute(),
        specification.remoteDesktop(),
        specification.humanTakeoverEnabled(),
        specification.agentPolicy(),
        specification.web3Workload(),
        specification.mediaWorkload(),
        specification.requestedMediaStreams(),
        specification.mediaBitrateKbps(),
        specification.videoRecording(),
        specification.extensionIds(),
        metadata);
  }

  private EnvironmentImportView toView(
      EnvironmentImportJobEntity job, List<EnvironmentImportItemEntity> importedItems) {
    return new EnvironmentImportView(
        job.getImportId(),
        job.getName(),
        job.getSchemaVersion(),
        job.getManifestHash(),
        job.getState(),
        job.getTotalCount(),
        job.getReadyCount(),
        job.getSucceededCount(),
        importedItems.stream().map(this::toItemView).toList(),
        job.getCreatedAt(),
        job.getUpdatedAt(),
        job.getCommittedAt(),
        job.getVersion());
  }

  private EnvironmentImportItemView toItemView(EnvironmentImportItemEntity item) {
    return new EnvironmentImportItemView(
        item.getItemId(),
        item.getItemIndex(),
        mapper.convertValue(item.getRequestPayload(), EnvironmentImportSpec.class),
        item.getValidationState(),
        item.getValidationErrors(),
        item.getExecutionState(),
        item.getSessionId(),
        item.getOperationId(),
        item.getRequestId(),
        item.getUpdatedAt());
  }

  private EnvironmentImportListItem toListItem(EnvironmentImportJobEntity job) {
    return new EnvironmentImportListItem(
        job.getImportId(),
        job.getName(),
        job.getState(),
        job.getTotalCount(),
        job.getReadyCount(),
        job.getSucceededCount(),
        job.getCreatedAt(),
        job.getUpdatedAt(),
        job.getVersion());
  }

  private void appendAudit(
      String tenantId,
      String actorId,
      String importId,
      String action,
      String result,
      String requestId,
      Map<String, Object> detail) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            null,
            "ENVIRONMENT_IMPORT",
            "USER",
            actorId,
            "ENVIRONMENT_IMPORT",
            importId,
            action,
            result,
            detail,
            requestId));
  }

  private byte[] write(Object value) {
    try {
      return mapper.writeValueAsBytes(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Environment Import serialization failed", exception);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private record PreparedItem(
      String itemId,
      int index,
      EnvironmentImportSpec specification,
      Map<String, Object> payload,
      String requestHash,
      List<String> errors) {}

  public static final class EnvironmentImportNotFoundException extends RuntimeException {}

  public static final class EnvironmentImportRejectedException extends RuntimeException {
    public EnvironmentImportRejectedException(String reason) {
      super(reason);
    }
  }
}
