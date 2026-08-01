package io.browsercloud.application;

import static io.browsercloud.api.WorkspaceBatchOperationModels.*;
import static io.browsercloud.application.CoordinatorCommandPayloads.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionListFilter;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.infrastructure.CoordinatorCommandQueue;
import io.browsercloud.infrastructure.SessionFilteredQueryRepository;
import io.browsercloud.persistence.SessionEntity;
import io.browsercloud.persistence.SessionJpaRepository;
import io.browsercloud.persistence.SessionMigrationEntity;
import io.browsercloud.persistence.SessionMigrationJpaRepository;
import io.browsercloud.persistence.WorkspaceBatchOperationEntity;
import io.browsercloud.persistence.WorkspaceBatchOperationItemEntity;
import io.browsercloud.persistence.WorkspaceBatchOperationItemJpaRepository;
import io.browsercloud.persistence.WorkspaceBatchOperationJpaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable Group/Tag/Session lifecycle batches backed by routed Coordinator commands. */
@Service
public class WorkspaceBatchOperationApplicationService {

  private static final int MAXIMUM_TARGETS = 100;
  private static final Duration COMMAND_DEADLINE = Duration.ofMinutes(15);

  private final WorkspaceBatchOperationJpaRepository operations;
  private final WorkspaceBatchOperationItemJpaRepository items;
  private final SessionJpaRepository sessions;
  private final SessionFilteredQueryRepository filteredSessions;
  private final WorkspaceGroupApplicationService groups;
  private final WorkspaceTagApplicationService tags;
  private final CoordinatorCommandRoutingService commandRouting;
  private final CoordinatorCommandQueue commandQueue;
  private final OperationRepository childOperations;
  private final SessionMigrationJpaRepository migrations;
  private final AuditApplicationService audit;
  private final ObjectMapper mapper;

  public WorkspaceBatchOperationApplicationService(
      WorkspaceBatchOperationJpaRepository operations,
      WorkspaceBatchOperationItemJpaRepository items,
      SessionJpaRepository sessions,
      SessionFilteredQueryRepository filteredSessions,
      WorkspaceGroupApplicationService groups,
      WorkspaceTagApplicationService tags,
      CoordinatorCommandRoutingService commandRouting,
      CoordinatorCommandQueue commandQueue,
      OperationRepository childOperations,
      SessionMigrationJpaRepository migrations,
      AuditApplicationService audit,
      ObjectMapper mapper) {
    this.operations = operations;
    this.items = items;
    this.sessions = sessions;
    this.filteredSessions = filteredSessions;
    this.groups = groups;
    this.tags = tags;
    this.commandRouting = commandRouting;
    this.commandQueue = commandQueue;
    this.childOperations = childOperations;
    this.migrations = migrations;
    this.audit = audit;
    this.mapper = mapper;
  }

  @Transactional
  public WorkspaceBatchOperationView create(
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      CreateWorkspaceBatchOperationRequest request) {
    var normalized = normalize(request);
    var requestHash = hash(writeCanonical(normalized));
    var replay = operations.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
    if (replay.isPresent()) {
      var existing = replay.orElseThrow();
      if (!existing.getRequestHash().equals(requestHash)) {
        throw new WorkspaceBatchOperationRejectedException("IDEMPOTENCY_KEY_REUSED");
      }
      return toViews(List.of(existing)).getFirst();
    }

    var targets = resolveTargets(tenantId, normalized.selector());
    if (targets.isEmpty()) {
      throw new WorkspaceBatchOperationRejectedException("BATCH_OPERATION_HAS_NO_TARGETS");
    }
    if (targets.size() > MAXIMUM_TARGETS) {
      throw new WorkspaceBatchOperationRejectedException("BATCH_OPERATION_TARGET_LIMIT_EXCEEDED");
    }

    var now = Instant.now();
    var batchOperationId = newId("bop_");
    var operation =
        operations.saveAndFlush(
            new WorkspaceBatchOperationEntity(
                batchOperationId,
                tenantId,
                actorId,
                normalized.action(),
                write(normalized.selector()),
                normalizeReason(normalized.reason()),
                requestHash,
                idempotencyKey,
                now));

    var batchItems = new java.util.ArrayList<WorkspaceBatchOperationItemEntity>();
    for (var ordinal = 0; ordinal < targets.size(); ordinal++) {
      var sessionId = targets.get(ordinal).getId();
      var commandId =
          commandRouting.enqueueAsync(
              sessionId,
              commandType(normalized.action()),
              batchOperationId + ":" + sessionId,
              payload(
                  normalized.action(), tenantId, actorId, batchOperationId, normalized.reason()),
              COMMAND_DEADLINE);
      batchItems.add(
          new WorkspaceBatchOperationItemEntity(
              newId("bopi_"), batchOperationId, tenantId, sessionId, ordinal, commandId, now));
    }
    items.saveAll(batchItems);
    appendAudit(
        operation,
        actorId,
        "WORKSPACE_BATCH_OPERATION_ACCEPTED",
        "ACCEPTED",
        requestId,
        Map.of(
            "action",
            normalized.action().name(),
            "targetCount",
            targets.size(),
            "confirmed",
            normalized.confirmed()));
    return toViews(List.of(operation)).getFirst();
  }

  @Transactional(readOnly = true)
  public WorkspaceBatchOperationView get(String tenantId, String batchOperationId) {
    return toViews(List.of(require(tenantId, batchOperationId))).getFirst();
  }

  @Transactional(readOnly = true)
  public WorkspaceBatchOperationListResponse list(String tenantId, int requestedLimit) {
    var limit = Math.max(1, Math.min(requestedLimit, 50));
    var entities =
        operations.findAllByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(0, limit));
    return new WorkspaceBatchOperationListResponse(
        toViews(entities), Math.toIntExact(operations.countByTenantId(tenantId)));
  }

  @Transactional
  public WorkspaceBatchOperationView cancel(
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
        throw new WorkspaceBatchOperationRejectedException("IDEMPOTENCY_KEY_REUSED");
      }
      return toViews(List.of(operation)).getFirst();
    }
    var now = Instant.now();
    operation.requestCancellation(now, cancellationHash, idempotencyKey);
    operations.save(operation);
    var operationItems = items.findAllByBatchOperationIdOrderByOrdinal(batchOperationId);
    var cancelled =
        commandQueue.cancelPending(
            operationItems.stream().map(WorkspaceBatchOperationItemEntity::getCommandId).toList(),
            now);
    appendAudit(
        operation,
        actorId,
        "WORKSPACE_BATCH_OPERATION_CANCELLATION_REQUESTED",
        "ACCEPTED",
        requestId,
        Map.of("cancelledPendingItems", cancelled, "reason", normalizedReason));
    return toViews(List.of(operation)).getFirst();
  }

  private List<SessionEntity> resolveTargets(String tenantId, WorkspaceBatchSelector selector) {
    if (!selector.sessionIds().isEmpty()) {
      var selected =
          sessions.findAllByTenantIdAndIdInOrderByCreatedAtDesc(tenantId, selector.sessionIds());
      if (selected.size() != selector.sessionIds().size()) {
        throw new WorkspaceBatchOperationRejectedException("BATCH_TARGET_NOT_FOUND");
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

  private CreateWorkspaceBatchOperationRequest normalize(
      CreateWorkspaceBatchOperationRequest request) {
    var selector = request.selector();
    var groupId = blankToNull(selector.groupId());
    var tagIds =
        selector.tagIds() == null
            ? List.<String>of()
            : selector.tagIds().stream().map(String::strip).distinct().sorted().toList();
    var sessionIds =
        selector.sessionIds() == null
            ? List.<String>of()
            : selector.sessionIds().stream().map(String::strip).distinct().sorted().toList();
    if (!sessionIds.isEmpty() && (groupId != null || !tagIds.isEmpty())) {
      throw new WorkspaceBatchOperationRejectedException("BATCH_SELECTOR_AMBIGUOUS");
    }
    if (sessionIds.isEmpty() && groupId == null && tagIds.isEmpty()) {
      throw new WorkspaceBatchOperationRejectedException("BATCH_SELECTOR_REQUIRED");
    }
    var reason = normalizeReason(request.reason());
    if (reason != null && reason.length() < 8) {
      throw new WorkspaceBatchOperationRejectedException("BATCH_REASON_TOO_SHORT");
    }
    if (request.action() != WorkspaceBatchAction.START) {
      if (!request.confirmed()) {
        throw new WorkspaceBatchOperationRejectedException("BATCH_RISK_CONFIRMATION_REQUIRED");
      }
      if (reason == null) {
        throw new WorkspaceBatchOperationRejectedException("BATCH_REASON_REQUIRED");
      }
    }
    return new CreateWorkspaceBatchOperationRequest(
        request.action(),
        new WorkspaceBatchSelector(
            groupId,
            tagIds,
            selector.tagMatch() == null ? TagMatch.ANY : selector.tagMatch(),
            sessionIds),
        reason,
        request.confirmed());
  }

  private List<WorkspaceBatchOperationView> toViews(
      List<WorkspaceBatchOperationEntity> batchOperations) {
    if (batchOperations.isEmpty()) {
      return List.of();
    }
    var batchIds =
        batchOperations.stream().map(WorkspaceBatchOperationEntity::getBatchOperationId).toList();
    var allItems = items.findAllByBatchOperationIdInOrderByBatchOperationIdAscOrdinalAsc(batchIds);
    var commands =
        commandQueue
            .findAllByIds(
                allItems.stream().map(WorkspaceBatchOperationItemEntity::getCommandId).toList())
            .stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    CoordinatorCommandQueue.CommandRecord::commandId, Function.identity()));
    var childReferences =
        commands.values().stream()
            .filter(command -> "COMMITTED".equals(command.state()))
            .map(command -> childReference(command.commandType(), command.result()))
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    var operationReferences =
        childOperations.findByIds(
            childReferences.stream().filter(reference -> reference.startsWith("op_")).toList());
    var migrationReferences =
        migrations
            .findAllById(
                childReferences.stream().filter(reference -> reference.startsWith("mig_")).toList())
            .stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    SessionMigrationEntity::getMigrationId, Function.identity()));
    var itemsByBatch =
        allItems.stream()
            .collect(
                Collectors.groupingBy(
                    WorkspaceBatchOperationItemEntity::getBatchOperationId,
                    LinkedHashMap::new,
                    Collectors.toList()));
    return batchOperations.stream()
        .map(
            operation ->
                toView(
                    operation,
                    itemsByBatch.getOrDefault(operation.getBatchOperationId(), List.of()),
                    commands,
                    operationReferences,
                    migrationReferences))
        .toList();
  }

  private WorkspaceBatchOperationView toView(
      WorkspaceBatchOperationEntity operation,
      List<WorkspaceBatchOperationItemEntity> operationItems,
      Map<String, CoordinatorCommandQueue.CommandRecord> commands,
      Map<String, io.browsercloud.domain.operation.ExclusiveOperation> childOperationsById,
      Map<String, SessionMigrationEntity> migrationsById) {
    var itemViews =
        operationItems.stream()
            .map(
                item ->
                    toItemView(
                        operation.getAction(),
                        item,
                        commands.get(item.getCommandId()),
                        childOperationsById,
                        migrationsById))
            .toList();
    var counts =
        itemViews.stream()
            .collect(
                Collectors.groupingBy(
                    WorkspaceBatchOperationItemView::state, Collectors.counting()));
    int accepted = count(counts, WorkspaceBatchItemState.ACCEPTED);
    int executing = count(counts, WorkspaceBatchItemState.EXECUTING);
    int succeeded = count(counts, WorkspaceBatchItemState.SUCCEEDED);
    int failed = count(counts, WorkspaceBatchItemState.FAILED);
    int cancelled = count(counts, WorkspaceBatchItemState.CANCELLED);
    var state =
        aggregateState(
            operation.getCancellationRequestedAt() != null,
            itemViews.size(),
            accepted,
            executing,
            succeeded,
            failed,
            cancelled);
    return new WorkspaceBatchOperationView(
        operation.getBatchOperationId(),
        operation.getAction(),
        state,
        read(operation.getSelector(), WorkspaceBatchSelector.class),
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

  private WorkspaceBatchOperationItemView toItemView(
      WorkspaceBatchAction action,
      WorkspaceBatchOperationItemEntity item,
      CoordinatorCommandQueue.CommandRecord command,
      Map<String, io.browsercloud.domain.operation.ExclusiveOperation> childOperationsById,
      Map<String, SessionMigrationEntity> migrationsById) {
    if (command == null) {
      return itemView(item, WorkspaceBatchItemState.FAILED, null, "COMMAND_LEDGER_NOT_FOUND", null);
    }
    return switch (command.state()) {
      case "PENDING" -> itemView(item, WorkspaceBatchItemState.ACCEPTED, null, null, command);
      case "EXECUTING" -> itemView(item, WorkspaceBatchItemState.EXECUTING, null, null, command);
      case "FAILED" ->
          itemView(
              item,
              "BATCH_OPERATION_CANCELLED".equals(command.failureCode())
                  ? WorkspaceBatchItemState.CANCELLED
                  : WorkspaceBatchItemState.FAILED,
              null,
              command.failureCode(),
              command);
      case "COMMITTED" -> committedItem(action, item, command, childOperationsById, migrationsById);
      default ->
          itemView(item, WorkspaceBatchItemState.FAILED, null, "COMMAND_STATE_INVALID", command);
    };
  }

  private WorkspaceBatchOperationItemView committedItem(
      WorkspaceBatchAction action,
      WorkspaceBatchOperationItemEntity item,
      CoordinatorCommandQueue.CommandRecord command,
      Map<String, io.browsercloud.domain.operation.ExclusiveOperation> childOperationsById,
      Map<String, SessionMigrationEntity> migrationsById) {
    if (action == WorkspaceBatchAction.PAUSE_AGENT) {
      return itemView(item, WorkspaceBatchItemState.SUCCEEDED, null, null, command);
    }
    var reference = childReference(command.commandType(), command.result());
    if (reference == null) {
      return itemView(
          item, WorkspaceBatchItemState.FAILED, null, "CHILD_REFERENCE_MISSING", command);
    }
    if (action == WorkspaceBatchAction.MIGRATE) {
      var migration = migrationsById.get(reference);
      if (migration == null) {
        return itemView(
            item, WorkspaceBatchItemState.FAILED, reference, "MIGRATION_LEDGER_NOT_FOUND", command);
      }
      if ("COMPLETED".equals(migration.getPhase())) {
        return itemView(item, WorkspaceBatchItemState.SUCCEEDED, reference, null, command);
      }
      if ("FAILED".equals(migration.getPhase())) {
        return itemView(
            item, WorkspaceBatchItemState.FAILED, reference, migration.getFailureReason(), command);
      }
      return itemView(item, WorkspaceBatchItemState.EXECUTING, reference, null, command);
    }
    var child = childOperationsById.get(reference);
    if (child == null) {
      return itemView(
          item, WorkspaceBatchItemState.FAILED, reference, "CHILD_OPERATION_NOT_FOUND", command);
    }
    if (child.state() == OperationState.COMMITTED) {
      return itemView(item, WorkspaceBatchItemState.SUCCEEDED, reference, null, command);
    }
    if (child.state() == OperationState.ACTIVE) {
      return itemView(item, WorkspaceBatchItemState.EXECUTING, reference, null, command);
    }
    return itemView(
        item,
        WorkspaceBatchItemState.FAILED,
        reference,
        "CHILD_OPERATION_" + child.state().name(),
        command);
  }

  private WorkspaceBatchOperationItemView itemView(
      WorkspaceBatchOperationItemEntity item,
      WorkspaceBatchItemState state,
      String childReference,
      String failureCode,
      CoordinatorCommandQueue.CommandRecord command) {
    return new WorkspaceBatchOperationItemView(
        item.getBatchItemId(),
        item.getSessionId(),
        item.getOrdinal(),
        item.getCommandId(),
        state,
        childReference,
        failureCode,
        item.getCreatedAt(),
        command == null ? null : command.startedAt(),
        command == null ? null : command.completedAt());
  }

  private String childReference(String commandType, String result) {
    if (result == null || result.isBlank()) {
      return null;
    }
    try {
      var tree = mapper.readTree(result);
      if (WORKSPACE_BATCH_MIGRATE.equals(commandType)) {
        return tree.path("migrationId").asText(null);
      }
      return tree.path("operationId").asText(null);
    } catch (JsonProcessingException exception) {
      return null;
    }
  }

  private WorkspaceBatchOperationEntity require(String tenantId, String batchOperationId) {
    return operations
        .findByBatchOperationIdAndTenantId(batchOperationId, tenantId)
        .orElseThrow(WorkspaceBatchOperationNotFoundException::new);
  }

  private Object payload(
      WorkspaceBatchAction action,
      String tenantId,
      String actorId,
      String batchOperationId,
      String reason) {
    if (action == WorkspaceBatchAction.START) {
      return new SessionActor(tenantId, actorId);
    }
    return new WorkspaceBatchSessionAction(
        tenantId, actorId, batchOperationId, normalizeReason(reason));
  }

  private String commandType(WorkspaceBatchAction action) {
    return switch (action) {
      case START -> SESSION_START;
      case PAUSE_AGENT -> WORKSPACE_BATCH_PAUSE_AGENT;
      case MIGRATE -> WORKSPACE_BATCH_MIGRATE;
      case HIBERNATE -> WORKSPACE_BATCH_HIBERNATE;
    };
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
    if (executing > 0) {
      return WorkspaceBatchState.EXECUTING;
    }
    if (accepted > 0) {
      return WorkspaceBatchState.ACCEPTED;
    }
    if (total > 0 && succeeded == total) {
      return WorkspaceBatchState.SUCCEEDED;
    }
    if (total > 0 && cancelled == total) {
      return WorkspaceBatchState.CANCELLED;
    }
    if (succeeded > 0 || cancelled > 0) {
      return WorkspaceBatchState.PARTIAL_SUCCESS;
    }
    if (failed > 0) {
      return WorkspaceBatchState.FAILED;
    }
    return WorkspaceBatchState.ACCEPTED;
  }

  private static int count(
      Map<WorkspaceBatchItemState, Long> counts, WorkspaceBatchItemState state) {
    return Math.toIntExact(counts.getOrDefault(state, 0L));
  }

  private void appendAudit(
      WorkspaceBatchOperationEntity operation,
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
            "WORKSPACE_BATCH_OPERATION",
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
      throw new WorkspaceBatchOperationRejectedException("BATCH_SELECTOR_INVALID");
    }
  }

  private String writeCanonical(Object value) {
    try {
      return mapper
          .writer()
          .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new WorkspaceBatchOperationRejectedException("BATCH_REQUEST_INVALID");
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored batch selector is invalid", exception);
    }
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

  private static String normalizeReason(String reason) {
    return reason == null || reason.isBlank() ? null : reason.strip();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  public static final class WorkspaceBatchOperationNotFoundException extends RuntimeException {}

  public static final class WorkspaceBatchOperationRejectedException extends RuntimeException {
    public WorkspaceBatchOperationRejectedException(String reason) {
      super(reason);
    }
  }
}
