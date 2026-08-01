package io.browsercloud.application;

import static io.browsercloud.api.WorkspaceBatchOperationModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.domain.agent.AgentPolicy;
import io.browsercloud.infrastructure.CoordinatorCommandQueue;
import io.browsercloud.infrastructure.CoordinatorCommandQueue.CommandRecord;
import io.browsercloud.infrastructure.SessionFilteredQueryRepository;
import io.browsercloud.persistence.SessionEntity;
import io.browsercloud.persistence.SessionJpaRepository;
import io.browsercloud.persistence.SessionMigrationJpaRepository;
import io.browsercloud.persistence.WorkspaceBatchOperationEntity;
import io.browsercloud.persistence.WorkspaceBatchOperationItemEntity;
import io.browsercloud.persistence.WorkspaceBatchOperationItemJpaRepository;
import io.browsercloud.persistence.WorkspaceBatchOperationJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceBatchOperationApplicationServiceTest {

  @Mock private WorkspaceBatchOperationJpaRepository operations;
  @Mock private WorkspaceBatchOperationItemJpaRepository items;
  @Mock private SessionJpaRepository sessions;
  @Mock private SessionFilteredQueryRepository filteredSessions;
  @Mock private WorkspaceGroupApplicationService groups;
  @Mock private WorkspaceTagApplicationService tags;
  @Mock private CoordinatorCommandRoutingService routing;
  @Mock private CoordinatorCommandQueue commandQueue;
  @Mock private OperationRepository childOperations;
  @Mock private SessionMigrationJpaRepository migrations;
  @Mock private AuditApplicationService audit;

  private WorkspaceBatchOperationApplicationService service;
  private final AtomicReference<List<WorkspaceBatchOperationItemEntity>> persistedItems =
      new AtomicReference<>(List.of());
  private final AtomicReference<WorkspaceBatchOperationEntity> persistedOperation =
      new AtomicReference<>();
  private final AtomicBoolean cancelled = new AtomicBoolean();

  @BeforeEach
  void setUp() {
    service =
        new WorkspaceBatchOperationApplicationService(
            operations,
            items,
            sessions,
            filteredSessions,
            groups,
            tags,
            routing,
            commandQueue,
            childOperations,
            migrations,
            audit,
            new ObjectMapper());
  }

  private void configurePersistenceAndViewStubs() {
    lenient()
        .when(operations.saveAndFlush(any(WorkspaceBatchOperationEntity.class)))
        .thenAnswer(
            invocation -> {
              WorkspaceBatchOperationEntity stored = invocation.getArgument(0);
              persistedOperation.set(stored);
              return stored;
            });
    lenient()
        .when(items.saveAll(anyList()))
        .thenAnswer(
            invocation -> {
              List<WorkspaceBatchOperationItemEntity> stored = invocation.getArgument(0);
              persistedItems.set(List.copyOf(stored));
              return stored;
            });
    when(items.findAllByBatchOperationIdInOrderByBatchOperationIdAscOrdinalAsc(anyList()))
        .thenAnswer(invocation -> persistedItems.get());
    lenient()
        .when(items.findAllByBatchOperationIdOrderByOrdinal(anyString()))
        .thenAnswer(invocation -> persistedItems.get());
    when(commandQueue.findAllByIds(anyList()))
        .thenAnswer(
            invocation ->
                persistedItems.get().stream()
                    .map(item -> command(item, cancelled.get() ? "FAILED" : "PENDING"))
                    .toList());
    when(childOperations.findByIds(anyList())).thenReturn(Map.of());
    when(migrations.findAllById(any())).thenReturn(List.of());
  }

  @Test
  void createsAndReplaysOneDurableRoutedCommandPerExplicitSession() {
    configurePersistenceAndViewStubs();
    var first = session("ses_1234567890abcdef");
    var second = session("ses_fedcba0987654321");
    var request =
        new CreateWorkspaceBatchOperationRequest(
            WorkspaceBatchAction.START,
            new WorkspaceBatchSelector(
                null, List.of(), TagMatch.ANY, List.of(first.getId(), second.getId())),
            null,
            false);
    when(operations.findByTenantIdAndIdempotencyKey("tenant-test", "idem-create"))
        .thenReturn(Optional.empty());
    when(sessions.findAllByTenantIdAndIdInOrderByCreatedAtDesc(
            "tenant-test", List.of(first.getId(), second.getId())))
        .thenReturn(List.of(second, first));
    var commandSequence = new AtomicInteger();
    when(routing.enqueueAsync(anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(invocation -> "ccmd_1234567890abcde" + commandSequence.incrementAndGet());

    var created =
        service.create("tenant-test", "operator-test", "idem-create", "request-1", request);
    when(operations.findByTenantIdAndIdempotencyKey("tenant-test", "idem-create"))
        .thenReturn(Optional.of(persistedOperation.get()));

    var replayed =
        service.create("tenant-test", "operator-test", "idem-create", "request-2", request);

    assertThat(created.total()).isEqualTo(2);
    assertThat(created.state()).isEqualTo(WorkspaceBatchState.ACCEPTED);
    assertThat(created.items())
        .extracting("sessionId")
        .containsExactly(first.getId(), second.getId());
    assertThat(replayed.batchOperationId()).isEqualTo(created.batchOperationId());
    verify(routing, times(2)).enqueueAsync(anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  void requiresConfirmationAndReasonForRiskyActions() {
    var selector =
        new WorkspaceBatchSelector(null, List.of(), TagMatch.ANY, List.of("ses_1234567890abcdef"));

    assertThatThrownBy(
            () ->
                service.create(
                    "tenant-test",
                    "operator-test",
                    "idem-risk",
                    "request-1",
                    new CreateWorkspaceBatchOperationRequest(
                        WorkspaceBatchAction.MIGRATE, selector, null, false)))
        .isInstanceOf(
            WorkspaceBatchOperationApplicationService.WorkspaceBatchOperationRejectedException
                .class)
        .hasMessage("BATCH_RISK_CONFIRMATION_REQUIRED");
  }

  @Test
  void cancellationIsIdempotentAndCancelsOnlyPendingCommands() {
    configurePersistenceAndViewStubs();
    var operation =
        capturedOperation(
            "bop_1234567890abcdef",
            new CreateWorkspaceBatchOperationRequest(
                WorkspaceBatchAction.START,
                new WorkspaceBatchSelector(
                    null, List.of(), TagMatch.ANY, List.of("ses_1234567890abcdef")),
                null,
                false));
    persistedItems.set(
        List.of(
            new WorkspaceBatchOperationItemEntity(
                "bopi_1234567890abcdef",
                operation.getBatchOperationId(),
                "tenant-test",
                "ses_1234567890abcdef",
                0,
                "ccmd_1234567890abcdef",
                Instant.parse("2026-07-31T00:00:00Z"))));
    when(operations.findByBatchOperationIdAndTenantId(
            operation.getBatchOperationId(), "tenant-test"))
        .thenReturn(Optional.of(operation));
    when(commandQueue.cancelPending(anyList(), any(Instant.class)))
        .thenAnswer(
            invocation -> {
              cancelled.set(true);
              return 1;
            });

    var first =
        service.cancel(
            "tenant-test",
            "operator-test",
            operation.getBatchOperationId(),
            "Operator cancelled pending items",
            "idem-cancel",
            "request-1");
    var replay =
        service.cancel(
            "tenant-test",
            "operator-test",
            operation.getBatchOperationId(),
            "Operator cancelled pending items",
            "idem-cancel",
            "request-2");

    assertThat(first.state()).isEqualTo(WorkspaceBatchState.CANCELLED);
    assertThat(replay.cancellationRequested()).isTrue();
    verify(commandQueue, times(1)).cancelPending(anyList(), any(Instant.class));
  }

  private WorkspaceBatchOperationEntity capturedOperation(
      String id, CreateWorkspaceBatchOperationRequest request) {
    return new WorkspaceBatchOperationEntity(
        id,
        "tenant-test",
        "operator-test",
        request.action(),
        new ObjectMapper().valueToTree(request.selector()).toString(),
        request.reason(),
        "0".repeat(64),
        "idem-create",
        Instant.parse("2026-07-31T00:00:00Z"));
  }

  private SessionEntity session(String sessionId) {
    return new SessionEntity(
        sessionId,
        "tenant-test",
        "profile-test",
        "local",
        "L2",
        "CREATED",
        "",
        "{}",
        true,
        AgentPolicy.BALANCED,
        "[]",
        Instant.parse("2026-07-31T00:00:00Z"));
  }

  private CommandRecord command(WorkspaceBatchOperationItemEntity item, String state) {
    return new CommandRecord(
        item.getCommandId(),
        "tenant-test",
        item.getSessionId(),
        1,
        0,
        CoordinatorCommandPayloads.SESSION_START,
        "dedupe",
        "{}",
        state,
        null,
        "FAILED".equals(state) ? "BATCH_OPERATION_CANCELLED" : null,
        0,
        null,
        null,
        Instant.parse("2026-07-31T00:15:00Z"),
        item.getCreatedAt(),
        null,
        "FAILED".equals(state) ? Instant.parse("2026-07-31T00:01:00Z") : null);
  }
}
