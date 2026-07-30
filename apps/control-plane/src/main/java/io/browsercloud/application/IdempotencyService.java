package io.browsercloud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.browsercloud.api.CreateAgentTaskRequest;
import io.browsercloud.api.CreateSessionRequest;
import io.browsercloud.api.StateResyncRequest;
import io.browsercloud.coordinator.exceptions.IdempotencyConflictException;
import io.browsercloud.persistence.ApiIdempotencyJpaRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** PostgreSQL 权威幂等记录；Redis 只能作为未来的加速层。 */
@Service
public class IdempotencyService {

  static final String CREATE_SESSION = "CREATE_SESSION";

  private final ApiIdempotencyJpaRepository repository;
  private final ObjectMapper canonicalMapper;

  IdempotencyService(ApiIdempotencyJpaRepository repository) {
    this.repository = repository;
    this.canonicalMapper =
        JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
  }

  String claimCreateSession(
      String tenantId,
      String idempotencyKey,
      CreateSessionRequest request,
      String candidateSessionId) {
    return claim(
        tenantId, CREATE_SESSION, idempotencyKey, hashRequest(request), candidateSessionId);
  }

  String claimStateResync(
      String tenantId,
      String sessionId,
      String idempotencyKey,
      StateResyncRequest request,
      String candidateRequestId) {
    return claim(
        tenantId,
        "STATE_RESYNC:" + sessionId,
        idempotencyKey,
        hashRequest(request),
        candidateRequestId);
  }

  String claimAgentTask(
      String tenantId,
      String sessionId,
      String idempotencyKey,
      CreateAgentTaskRequest request,
      String candidateTaskId) {
    return claim(
        tenantId,
        "CREATE_AGENT_TASK:" + sessionId,
        idempotencyKey,
        hashRequest(request),
        candidateTaskId);
  }

  String claimAgentExecution(
      String tenantId, String taskId, String idempotencyKey, String candidateOperationId) {
    return claim(
        tenantId,
        "EXECUTE_AGENT_TASK:" + taskId,
        idempotencyKey,
        hashRequest(taskId),
        candidateOperationId);
  }

  String claimResourcePolicy(
      String tenantId,
      String sessionId,
      String idempotencyKey,
      Object request,
      String candidateOperationId) {
    return claim(
        tenantId,
        "UPDATE_RESOURCE_POLICY:" + sessionId,
        idempotencyKey,
        hashRequest(request),
        candidateOperationId);
  }

  String claimSafetyLease(
      String tenantId,
      String sessionId,
      String actorId,
      String idempotencyKey,
      Object request,
      String candidateLeaseId) {
    return claim(
        tenantId,
        "CREATE_SAFETY_LEASE:" + sessionId + ":" + actorId,
        idempotencyKey,
        hashRequest(request),
        candidateLeaseId);
  }

  String claimSafetyLeaseMutation(
      String tenantId,
      String leaseId,
      String actorId,
      String mutation,
      String idempotencyKey,
      Object request,
      String candidateEventId) {
    return claim(
        tenantId,
        mutation + "_SAFETY_LEASE:" + leaseId + ":" + actorId,
        idempotencyKey,
        hashRequest(request),
        candidateEventId);
  }

  String claimBusinessRecoveryValidation(
      String tenantId,
      String sessionId,
      String idempotencyKey,
      String source,
      String candidateValidationId) {
    return claim(
        tenantId,
        "BUSINESS_RECOVERY_VALIDATION:" + sessionId,
        idempotencyKey,
        hashRequest(source),
        candidateValidationId);
  }

  String claimBusinessRecoveryProviderEvidence(
      String tenantId,
      String sessionId,
      String adapterActorId,
      String idempotencyKey,
      Object request,
      String candidateEvidenceId) {
    return claim(
        tenantId,
        "BUSINESS_RECOVERY_PROVIDER_EVIDENCE:" + sessionId + ":" + adapterActorId,
        idempotencyKey,
        hashRequest(request),
        candidateEvidenceId);
  }

  String claimApplicationBindingRebind(
      String tenantId,
      String sessionId,
      String idempotencyKey,
      Object request,
      String candidateOperationId) {
    return claim(
        tenantId,
        "REBIND_APPLICATION_CONTRACT:" + sessionId,
        idempotencyKey,
        hashRequest(request),
        candidateOperationId);
  }

  String claimRecoveryContractRestore(
      String tenantId,
      String applicationId,
      String idempotencyKey,
      Object request,
      String candidateRevisionId) {
    return claim(
        tenantId,
        "RESTORE_RECOVERY_CONTRACT:" + applicationId,
        idempotencyKey,
        hashRequest(request),
        candidateRevisionId);
  }

  String claimWorkspaceGroupCreate(
      String tenantId, String idempotencyKey, Object request, String candidateGroupId) {
    return claim(
        tenantId, "CREATE_WORKSPACE_GROUP", idempotencyKey, hashRequest(request), candidateGroupId);
  }

  String claimWorkspaceGroupMutation(
      String tenantId,
      String groupId,
      String mutation,
      String idempotencyKey,
      Object request,
      String candidateMutationId) {
    return claim(
        tenantId,
        mutation + "_WORKSPACE_GROUP:" + groupId,
        idempotencyKey,
        hashRequest(request),
        candidateMutationId);
  }

  String claimWorkspaceTagCreate(
      String tenantId, String idempotencyKey, Object request, String candidateTagId) {
    return claim(
        tenantId, "CREATE_WORKSPACE_TAG", idempotencyKey, hashRequest(request), candidateTagId);
  }

  String claimWorkspaceTagMutation(
      String tenantId,
      String tagId,
      String mutation,
      String idempotencyKey,
      Object request,
      String candidateMutationId) {
    return claim(
        tenantId,
        mutation + "_WORKSPACE_TAG:" + tagId,
        idempotencyKey,
        hashRequest(request),
        candidateMutationId);
  }

  String claimWorkspaceSettingsUpdate(
      String tenantId, String idempotencyKey, Object request, String candidateMutationId) {
    return claim(
        tenantId,
        "UPDATE_WORKSPACE_SETTINGS",
        idempotencyKey,
        hashRequest(request),
        candidateMutationId);
  }

  String claimProxyBindingCreate(
      String tenantId, String idempotencyKey, Object request, String candidateBindingProfileId) {
    return claim(
        tenantId,
        "CREATE_PROXY_BINDING",
        idempotencyKey,
        hashRequest(request),
        candidateBindingProfileId);
  }

  String claimProxyBindingMutation(
      String tenantId,
      String bindingProfileId,
      String mutation,
      String idempotencyKey,
      Object request,
      String candidateMutationId) {
    return claim(
        tenantId,
        mutation + "_PROXY_BINDING:" + bindingProfileId,
        idempotencyKey,
        hashRequest(request),
        candidateMutationId);
  }

  String claimEnvironmentSavedViewCreate(
      String tenantId,
      String actorId,
      String idempotencyKey,
      Object request,
      String candidateSavedViewId) {
    return claim(
        tenantId,
        "CREATE_ENVIRONMENT_SAVED_VIEW:" + actorId,
        idempotencyKey,
        hashRequest(request),
        candidateSavedViewId);
  }

  String claimEnvironmentSavedViewMutation(
      String tenantId,
      String savedViewId,
      String actorId,
      String mutation,
      String idempotencyKey,
      Object request,
      String candidateMutationId) {
    return claim(
        tenantId,
        mutation + "_ENVIRONMENT_SAVED_VIEW:" + savedViewId + ":" + actorId,
        idempotencyKey,
        hashRequest(request),
        candidateMutationId);
  }

  String claimEnvironmentImportPreview(
      String tenantId,
      String actorId,
      String idempotencyKey,
      Object request,
      String candidateImportId) {
    return claim(
        tenantId,
        "PREVIEW_ENVIRONMENT_IMPORT:" + actorId,
        idempotencyKey,
        hashRequest(request),
        candidateImportId);
  }

  String claimEnvironmentImportCommit(
      String tenantId,
      String actorId,
      String importId,
      String idempotencyKey,
      Object request,
      String candidateMutationId) {
    return claim(
        tenantId,
        "COMMIT_ENVIRONMENT_IMPORT:" + importId + ":" + actorId,
        idempotencyKey,
        hashRequest(request),
        candidateMutationId);
  }

  String claimTenantRouteMigration(
      String tenantId, String idempotencyKey, Object request, String candidateMigrationId) {
    return claim(
        tenantId,
        "MIGRATE_TENANT_COORDINATOR_ROUTE",
        idempotencyKey,
        hashRequest(request),
        candidateMigrationId);
  }

  private String claim(
      String tenantId,
      String operationType,
      String idempotencyKey,
      String requestHash,
      String candidateResourceId) {
    int claimed =
        repository.claim(
            newId("idem_"),
            tenantId,
            operationType,
            idempotencyKey,
            requestHash,
            candidateResourceId,
            Instant.now());
    if (claimed == 1) {
      return candidateResourceId;
    }
    return repository
        .findByTenantIdAndOperationTypeAndIdempotencyKey(tenantId, operationType, idempotencyKey)
        .map(
            record -> {
              if (!record.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException();
              }
              return record.getResourceId();
            })
        .orElseThrow(() -> new IllegalStateException("Idempotency claim disappeared"));
  }

  private String hashRequest(Object request) {
    try {
      byte[] json = canonicalMapper.writeValueAsBytes(request);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
      return HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Failed to hash idempotent request", exception);
    }
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }
}
