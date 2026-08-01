package io.browsercloud.application;

import static io.browsercloud.api.WorkspaceBatchOperationModels.TagMatch;
import static io.browsercloud.api.WorkspaceBatchOperationModels.WorkspaceBatchItemState;
import static io.browsercloud.api.WorkspaceBatchOperationModels.WorkspaceBatchState;
import static io.browsercloud.api.WorkspaceMetadataBatchOperationModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.browsercloud.coordinator.SessionListFilter;
import io.browsercloud.infrastructure.SessionFilteredQueryRepository;
import io.browsercloud.infrastructure.WorkspaceMetadataBatchClaimStore;
import io.browsercloud.infrastructure.WorkspaceMetadataBatchClaimStore.NewOperation;
import io.browsercloud.persistence.SessionEntity;
import io.browsercloud.persistence.SessionJpaRepository;
import io.browsercloud.persistence.WorkspaceMetadataBatchOperationEntity;
import io.browsercloud.persistence.WorkspaceMetadataBatchOperationItemEntity;
import io.browsercloud.persistence.WorkspaceMetadataBatchOperationItemJpaRepository;
import io.browsercloud.persistence.WorkspaceMetadataBatchOperationJpaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, bounded Group/Tag membership mutations with per-Session execution evidence. */
@Service
public class WorkspaceMetadataBatchOperationApplicationService {

  private static final int MAXIMUM_TARGETS = 100;
  private static final Duration OPERATION_DEADLINE = Duration.ofMinutes(15);

  private final WorkspaceMetadataBatchOperationJpaRepository operations;
  private final WorkspaceMetadataBatchOperationItemJpaRepository items;
  private final SessionJpaRepository sessions;
  private final SessionFilteredQueryRepository filteredSessions;
  private final WorkspaceGroupApplicationService groups;
  private final WorkspaceTagApplicationService tags;
  private final WorkspaceMetadataBatchClaimStore claims;
  private final AuditApplicationService audit;
  private final ObjectMapper mapper;

  public WorkspaceMetadataBatchOperationApplicationService(
      WorkspaceMetadataBatchOperationJpaRepository operations,
      WorkspaceMetadataBatchOperationItemJpaRepository items,
      SessionJpaRepository sessions,
      SessionFilteredQueryRepository filteredSessions,
      WorkspaceGroupApplicationService groups,
      WorkspaceTagApplicationService tags,
      WorkspaceMetadataBatchClaimStore claims,
      AuditApplicationService audit,
      ObjectMapper mapper) {
    this.operations = operations;
    this.items = items;
    this.sessions = sessions;
    this.filteredSessions = filteredSessions;
    this.groups = groups;
    this.tags = tags;
    this.claims = claims;
    this.audit = audit;
    this.mapper = mapper;
  }

  @Transactional
  public WorkspaceMetadataBatchOperationView create(
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      CreateWorkspaceMetadataBatchOperationRequest request) {
    var normalized = normalize(request);
    var requestHash = hash(writeCanonical(normalized));
    var replay = operations.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
    if (replay.isPresent()) {
      return replay(replay.orElseThrow(), requestHash);
    }

    validateTargetReferences(tenantId, normalized);
    var targets = resolveTargets(tenantId, normalized.selector());
    if (targets.isEmpty()) {
      throw new WorkspaceMetadataBatchOperationRejectedException("METADATA_BATCH_HAS_NO_TARGETS");
    }
    if (targets.size() > MAXIMUM_TARGETS) {
      throw new WorkspaceMetadataBatchOperationRejectedException(
          "METADATA_BATCH_TARGET_LIMIT_EXCEEDED");
    }

    var now = Instant.now();
    var candidateId = newId("mbop_");
    var claimed =
        claims.claimOperation(
            new NewOperation(
                candidateId,
                tenantId,
                actorId,
                normalized.action().name(),
                write(normalized.selector()),
                normalized.target().groupId(),
                write(normalized.target().tagIds()),
                normalized.reason(),
                requestHash,
                idempotencyKey,
                now.plus(OPERATION_DEADLINE),
                now));
    if (!candidateId.equals(claimed.batchOperationId())) {
      return replay(require(tenantId, claimed.batchOperationId()), requestHash);
    }

    var batchItems = new java.util.ArrayList<WorkspaceMetadataBatchOperationItemEntity>();
    for (var ordinal = 0; ordinal < targets.size(); ordinal++) {
      batchItems.add(
          new WorkspaceMetadataBatchOperationItemEntity(
              newId("mbopi_"), candidateId, tenantId, targets.get(ordinal).getId(), ordinal, now));
    }
    items.saveAllAndFlush(batchItems);
    var operation = require(tenantId, candidateId);
    appendAudit(
        operation,
        actorId,
        "WORKSPACE_METADATA_BATCH_ACCEPTED",
        "ACCEPTED",
        requestId,
        Map.of(
            "action",
            operation.getAction().name(),
            "targetCount",
            targets.size(),
            "confirmed",
            normalized.confirmed()));
    return toViews(List.of(operation)).getFirst();
  }

  @Transactional(readOnly = true)
  public WorkspaceMetadataBatchOperationView get(String tenantId, String batchOperationId) {
    return toViews(List.of(require(tenantId, batchOperationId))).getFirst();
  }

  @Transactional(readOnly = true)
  public WorkspaceMetadataBatchOperationListResponse list(String tenantId, int requestedLimit) {
    var limit = Math.max(1, Math.min(requestedLimit, 50));
    var entities =
        operations.findAllByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(0, limit));
    return new WorkspaceMetadataBatchOperationListResponse(
        toViews(entities), Math.toIntExact(operations.countByTenantId(tenantId)));
  }

  @Transactional
  public WorkspaceMetadataBatchOperationView cancel(
      String tenantId,
      String actorId,
      String batchOperationId,
      String reason,
      String idempotencyKey,
      String requestId) {
    var operation = require(tenantId, batchOperationId);
    var normalizedReason = reason.strip();
    var cancellationHash = hash(actorId + "\n" + normalizedReason);
    if (operation.getCancellationRequestedAt() != null) {
      if (idempotencyKey.equals(operation.getCancellationIdempotencyKey())
          && !cancellationHash.equals(operation.getCancellationRequestHash())) {
        throw new WorkspaceMetadataBatchOperationRejectedException("IDEMPOTENCY_KEY_REUSED");
      }
      return toViews(List.of(operation)).getFirst();
    }
    var now = Instant.now();
    operation.requestCancellation(now, cancellationHash, idempotencyKey);
    operations.saveAndFlush(operation);
    var cancelled = claims.cancelPending(batchOperationId, now);
    appendAudit(
        operation,
        actorId,
        "WORKSPACE_METADATA_BATCH_CANCELLATION_REQUESTED",
        "ACCEPTED",
        requestId,
        Map.of("cancelledPendingItems", cancelled, "reason", normalizedReason));
    return toViews(List.of(operation)).getFirst();
  }

  @Transactional
  public void executeClaimed(String batchItemId) {
    var item = claims.requireClaimedForUpdate(batchItemId);
    var operation = require(item.tenantId(), item.batchOperationId());
    var requestId = "metadata-batch:" + item.batchItemId();
    switch (operation.getAction()) {
      case ASSIGN_GROUP ->
          groups.assignForMetadataBatch(
              item.tenantId(),
              operation.getActorId(),
              operation.getTargetGroupId(),
              item.sessionId(),
              operation.getBatchOperationId(),
              requestId);
      case REMOVE_GROUP ->
          groups.unassignForMetadataBatch(
              item.tenantId(),
              operation.getActorId(),
              operation.getTargetGroupId(),
              item.sessionId(),
              operation.getBatchOperationId(),
              requestId);
      case ASSIGN_TAGS -> {
        for (var tagId : operation.getTargetTagIds()) {
          tags.assignForMetadataBatch(
              item.tenantId(),
              operation.getActorId(),
              tagId,
              item.sessionId(),
              operation.getBatchOperationId(),
              requestId);
        }
      }
      case REMOVE_TAGS -> {
        for (var tagId : operation.getTargetTagIds()) {
          tags.unassignForMetadataBatch(
              item.tenantId(),
              operation.getActorId(),
              tagId,
              item.sessionId(),
              operation.getBatchOperationId(),
              requestId);
        }
      }
    }
    claims.commit(item.batchItemId(), item.batchOperationId(), Instant.now());
  }

  private WorkspaceMetadataBatchOperationView replay(
      WorkspaceMetadataBatchOperationEntity operation, String requestHash) {
    if (!operation.getRequestHash().equals(requestHash)) {
      throw new WorkspaceMetadataBatchOperationRejectedException("IDEMPOTENCY_KEY_REUSED");
    }
    return toViews(List.of(operation)).getFirst();
  }

  private List<SessionEntity> resolveTargets(
      String tenantId, WorkspaceMetadataBatchSelector selector) {
    if (!selector.sessionIds().isEmpty()) {
      var selected =
          sessions.findAllByTenantIdAndIdInOrderByCreatedAtDesc(tenantId, selector.sessionIds());
      if (selected.size() != selector.sessionIds().size()) {
        throw new WorkspaceMetadataBatchOperationRejectedException(
            "METADATA_BATCH_TARGET_NOT_FOUND");
      }
      return selected.stream()
          .sorted(java.util.Comparator.comparing(SessionEntity::getId))
          .toList();
    }
    groups.requireExists(tenantId, selector.groupId());
    tags.requireAllExist(tenantId, selector.tagIds());
    var filter =
        new SessionListFilter(
            selector.groupId(),
            selector.tagIds(),
            selector.tagMatch() == TagMatch.ALL
                ? SessionListFilter.TagMatch.ALL
                : SessionListFilter.TagMatch.ANY);
    return filteredSessions.list(tenantId, null, "", filter, MAXIMUM_TARGETS + 1, 0);
  }

  private void validateTargetReferences(
      String tenantId, CreateWorkspaceMetadataBatchOperationRequest request) {
    if (request.target().groupId() != null) {
      groups.requireExists(tenantId, request.target().groupId());
    }
    tags.requireAllExist(tenantId, request.target().tagIds());
  }

  private CreateWorkspaceMetadataBatchOperationRequest normalize(
      CreateWorkspaceMetadataBatchOperationRequest request) {
    var selector = request.selector();
    var groupId = blankToNull(selector.groupId());
    var selectorTagIds = normalizeIds(selector.tagIds());
    var sessionIds = normalizeIds(selector.sessionIds());
    if (!sessionIds.isEmpty() && (groupId != null || !selectorTagIds.isEmpty())) {
      throw new WorkspaceMetadataBatchOperationRejectedException(
          "METADATA_BATCH_SELECTOR_AMBIGUOUS");
    }
    if (sessionIds.isEmpty() && groupId == null && selectorTagIds.isEmpty()) {
      throw new WorkspaceMetadataBatchOperationRejectedException(
          "METADATA_BATCH_SELECTOR_REQUIRED");
    }

    var targetGroupId = blankToNull(request.target().groupId());
    var targetTagIds = normalizeIds(request.target().tagIds());
    var groupAction =
        request.action() == WorkspaceMetadataBatchAction.ASSIGN_GROUP
            || request.action() == WorkspaceMetadataBatchAction.REMOVE_GROUP;
    if (groupAction && (targetGroupId == null || !targetTagIds.isEmpty())) {
      throw new WorkspaceMetadataBatchOperationRejectedException(
          "METADATA_BATCH_GROUP_TARGET_REQUIRED");
    }
    if (!groupAction && (targetGroupId != null || targetTagIds.isEmpty())) {
      throw new WorkspaceMetadataBatchOperationRejectedException(
          "METADATA_BATCH_TAG_TARGET_REQUIRED");
    }
    if (!request.confirmed()) {
      throw new WorkspaceMetadataBatchOperationRejectedException(
          "METADATA_BATCH_CONFIRMATION_REQUIRED");
    }
    var reason = request.reason().strip();
    if (reason.length() < 8) {
      throw new WorkspaceMetadataBatchOperationRejectedException("METADATA_BATCH_REASON_TOO_SHORT");
    }
    return new CreateWorkspaceMetadataBatchOperationRequest(
        request.action(),
        new WorkspaceMetadataBatchSelector(
            groupId,
            selectorTagIds,
            selector.tagMatch() == null ? TagMatch.ANY : selector.tagMatch(),
            sessionIds),
        new WorkspaceMetadataBatchTarget(targetGroupId, targetTagIds),
        reason,
        true);
  }

  private List<WorkspaceMetadataBatchOperationView> toViews(
      List<WorkspaceMetadataBatchOperationEntity> batchOperations) {
    if (batchOperations.isEmpty()) {
      return List.of();
    }
    var batchIds =
        batchOperations.stream()
            .map(WorkspaceMetadataBatchOperationEntity::getBatchOperationId)
            .toList();
    var itemsByBatch =
        items.findAllByBatchOperationIdInOrderByBatchOperationIdAscOrdinalAsc(batchIds).stream()
            .collect(
                Collectors.groupingBy(
                    WorkspaceMetadataBatchOperationItemEntity::getBatchOperationId,
                    LinkedHashMap::new,
                    Collectors.toList()));
    return batchOperations.stream()
        .map(
            operation ->
                toView(
                    operation,
                    itemsByBatch.getOrDefault(operation.getBatchOperationId(), List.of())))
        .toList();
  }

  private WorkspaceMetadataBatchOperationView toView(
      WorkspaceMetadataBatchOperationEntity operation,
      List<WorkspaceMetadataBatchOperationItemEntity> operationItems) {
    var itemViews =
        operationItems.stream()
            .map(
                item ->
                    new WorkspaceMetadataBatchOperationItemView(
                        item.getBatchItemId(),
                        item.getSessionId(),
                        item.getOrdinal(),
                        item.getState(),
                        item.getFailureCode(),
                        item.getAttempt(),
                        item.getCreatedAt(),
                        item.getStartedAt(),
                        item.getCompletedAt()))
            .toList();
    var counts =
        itemViews.stream()
            .collect(
                Collectors.groupingBy(
                    WorkspaceMetadataBatchOperationItemView::state, Collectors.counting()));
    int accepted = count(counts, WorkspaceBatchItemState.ACCEPTED);
    int executing = count(counts, WorkspaceBatchItemState.EXECUTING);
    int succeeded = count(counts, WorkspaceBatchItemState.SUCCEEDED);
    int failed = count(counts, WorkspaceBatchItemState.FAILED);
    int cancelled = count(counts, WorkspaceBatchItemState.CANCELLED);
    return new WorkspaceMetadataBatchOperationView(
        operation.getBatchOperationId(),
        operation.getAction(),
        aggregateState(
            operation.getCancellationRequestedAt() != null,
            itemViews.size(),
            accepted,
            executing,
            succeeded,
            failed,
            cancelled),
        read(operation.getSelector(), WorkspaceMetadataBatchSelector.class),
        new WorkspaceMetadataBatchTarget(operation.getTargetGroupId(), operation.getTargetTagIds()),
        operation.getReason(),
        itemViews.size(),
        accepted,
        executing,
        succeeded,
        failed,
        cancelled,
        operation.getCancellationRequestedAt() != null,
        itemViews,
        operation.getActorId(),
        operation.getCreatedAt(),
        operation.getUpdatedAt());
  }

  private WorkspaceMetadataBatchOperationEntity require(String tenantId, String batchOperationId) {
    return operations
        .findByBatchOperationIdAndTenantId(batchOperationId, tenantId)
        .orElseThrow(WorkspaceMetadataBatchOperationNotFoundException::new);
  }

  private void appendAudit(
      WorkspaceMetadataBatchOperationEntity operation,
      String actorId,
      String eventType,
      String result,
      String requestId,
      Map<String, Object> details) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            operation.getTenantId(),
            null,
            eventType,
            "USER",
            actorId,
            "WORKSPACE_METADATA_BATCH_OPERATION",
            operation.getBatchOperationId(),
            operation.getAction().name(),
            result,
            details,
            requestId));
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new WorkspaceMetadataBatchOperationRejectedException("METADATA_BATCH_PAYLOAD_INVALID");
    }
  }

  private String writeCanonical(Object value) {
    try {
      return mapper
          .writer()
          .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new WorkspaceMetadataBatchOperationRejectedException("METADATA_BATCH_REQUEST_INVALID");
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored metadata batch payload is invalid", exception);
    }
  }

  private static WorkspaceBatchState aggregateState(
      boolean cancellationRequested,
      int total,
      int accepted,
      int executing,
      int succeeded,
      int failed,
      int cancelled) {
    if (cancellationRequested && accepted + executing > 0) {
      return WorkspaceBatchState.CANCELLING;
    }
    if (executing > 0) return WorkspaceBatchState.EXECUTING;
    if (accepted > 0) return WorkspaceBatchState.ACCEPTED;
    if (total > 0 && succeeded == total) return WorkspaceBatchState.SUCCEEDED;
    if (total > 0 && cancelled == total) return WorkspaceBatchState.CANCELLED;
    if (succeeded > 0 || cancelled > 0) return WorkspaceBatchState.PARTIAL_SUCCESS;
    if (failed > 0) return WorkspaceBatchState.FAILED;
    return WorkspaceBatchState.ACCEPTED;
  }

  private static int count(
      Map<WorkspaceBatchItemState, Long> counts, WorkspaceBatchItemState state) {
    return Math.toIntExact(counts.getOrDefault(state, 0L));
  }

  private static List<String> normalizeIds(List<String> values) {
    if (values == null || values.isEmpty()) return List.of();
    return values.stream().map(String::strip).distinct().sorted().toList();
  }

  private static String hash(String value) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of()
          .formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  public static final class WorkspaceMetadataBatchOperationNotFoundException
      extends RuntimeException {}

  public static final class WorkspaceMetadataBatchOperationRejectedException
      extends RuntimeException {
    public WorkspaceMetadataBatchOperationRejectedException(String reason) {
      super(reason);
    }
  }
}
