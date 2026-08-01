package io.browsercloud.application;

import static io.browsercloud.api.WorkspaceBatchOperationModels.TagMatch.ANY;
import static io.browsercloud.api.WorkspaceMetadataBatchOperationModels.WorkspaceMetadataBatchAction.ASSIGN_GROUP;
import static io.browsercloud.api.WorkspaceMetadataBatchOperationModels.WorkspaceMetadataBatchAction.ASSIGN_TAGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.WorkspaceMetadataBatchOperationModels.CreateWorkspaceMetadataBatchOperationRequest;
import io.browsercloud.api.WorkspaceMetadataBatchOperationModels.WorkspaceMetadataBatchSelector;
import io.browsercloud.api.WorkspaceMetadataBatchOperationModels.WorkspaceMetadataBatchTarget;
import io.browsercloud.infrastructure.SessionFilteredQueryRepository;
import io.browsercloud.infrastructure.WorkspaceMetadataBatchClaimStore;
import io.browsercloud.infrastructure.WorkspaceMetadataBatchClaimStore.ClaimedItem;
import io.browsercloud.infrastructure.WorkspaceMetadataBatchClaimStore.ClaimedOperation;
import io.browsercloud.infrastructure.WorkspaceMetadataBatchClaimStore.NewOperation;
import io.browsercloud.persistence.SessionEntity;
import io.browsercloud.persistence.SessionJpaRepository;
import io.browsercloud.persistence.WorkspaceMetadataBatchOperationEntity;
import io.browsercloud.persistence.WorkspaceMetadataBatchOperationItemEntity;
import io.browsercloud.persistence.WorkspaceMetadataBatchOperationItemJpaRepository;
import io.browsercloud.persistence.WorkspaceMetadataBatchOperationJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceMetadataBatchOperationApplicationServiceTest {

  @Mock private WorkspaceMetadataBatchOperationJpaRepository operations;
  @Mock private WorkspaceMetadataBatchOperationItemJpaRepository items;
  @Mock private SessionJpaRepository sessions;
  @Mock private SessionFilteredQueryRepository filteredSessions;
  @Mock private WorkspaceGroupApplicationService groups;
  @Mock private WorkspaceTagApplicationService tags;
  @Mock private WorkspaceMetadataBatchClaimStore claims;
  @Mock private AuditApplicationService audit;

  private WorkspaceMetadataBatchOperationApplicationService service;
  private final AtomicReference<NewOperation> claimed = new AtomicReference<>();
  private final AtomicReference<List<WorkspaceMetadataBatchOperationItemEntity>> persistedItems =
      new AtomicReference<>(List.of());

  @BeforeEach
  void setUp() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    service =
        new WorkspaceMetadataBatchOperationApplicationService(
            operations, items, sessions, filteredSessions, groups, tags, claims, audit, mapper);
  }

  @Test
  void persistsNormalizedGroupAssignmentWithPerSessionItems() {
    var sessionB = session("ses_fedcba0987654321");
    var sessionA = session("ses_1234567890abcdef");
    var request =
        new CreateWorkspaceMetadataBatchOperationRequest(
            ASSIGN_GROUP,
            new WorkspaceMetadataBatchSelector(
                null,
                List.of(),
                ANY,
                List.of(sessionB.getId(), sessionA.getId(), sessionA.getId())),
            new WorkspaceMetadataBatchTarget("grp_1234567890abcdef", List.of()),
            "Move CRM sessions",
            true);
    when(operations.findByTenantIdAndIdempotencyKey("tenant-a", "metadata-key"))
        .thenReturn(Optional.empty());
    when(items.saveAllAndFlush(any()))
        .thenAnswer(
            invocation -> {
              List<WorkspaceMetadataBatchOperationItemEntity> stored = invocation.getArgument(0);
              persistedItems.set(stored);
              return stored;
            });
    when(items.findAllByBatchOperationIdInOrderByBatchOperationIdAscOrdinalAsc(any()))
        .thenAnswer(invocation -> persistedItems.get());
    when(sessions.findAllByTenantIdAndIdInOrderByCreatedAtDesc(eq("tenant-a"), any()))
        .thenReturn(List.of(sessionB, sessionA));
    when(claims.claimOperation(any()))
        .thenAnswer(
            invocation -> {
              NewOperation value = invocation.getArgument(0);
              claimed.set(value);
              return new ClaimedOperation(value.batchOperationId(), value.requestHash());
            });
    when(operations.findByBatchOperationIdAndTenantId(any(), eq("tenant-a")))
        .thenAnswer(invocation -> Optional.of(entity(claimed.get())));

    var result = service.create("tenant-a", "operator-a", "metadata-key", "request-a", request);

    assertThat(result.action()).isEqualTo(ASSIGN_GROUP);
    assertThat(result.total()).isEqualTo(2);
    assertThat(result.items())
        .extracting(item -> item.sessionId())
        .containsExactly(sessionA.getId(), sessionB.getId());
    assertThat(claimed.get().targetGroupId()).isEqualTo("grp_1234567890abcdef");
    verify(groups).requireExists("tenant-a", "grp_1234567890abcdef");
    verify(audit).append(any());
  }

  @Test
  void rejectsTagActionWithoutTagTarget() {
    var request =
        new CreateWorkspaceMetadataBatchOperationRequest(
            ASSIGN_TAGS,
            new WorkspaceMetadataBatchSelector(
                null, List.of(), ANY, List.of("ses_1234567890abcdef")),
            new WorkspaceMetadataBatchTarget(null, List.of()),
            "Assign tags safely",
            true);

    assertThatThrownBy(
            () -> service.create("tenant-a", "operator-a", "metadata-key", "request-a", request))
        .isInstanceOf(
            WorkspaceMetadataBatchOperationApplicationService
                .WorkspaceMetadataBatchOperationRejectedException.class)
        .hasMessage("METADATA_BATCH_TAG_TARGET_REQUIRED");
  }

  @Test
  void executesAllTagAssignmentsAtomicallyBeforeCommittingItem() {
    var operation =
        new WorkspaceMetadataBatchOperationEntity(
            "mbop_1234567890abcdef",
            "tenant-a",
            "operator-a",
            ASSIGN_TAGS,
            "{\"groupId\":null,\"tagIds\":[],\"tagMatch\":\"ANY\",\"sessionIds\":[\"ses_1234567890abcdef\"]}",
            null,
            List.of("tag_1234567890abcdef", "tag_fedcba0987654321"),
            "Assign trusted tags",
            "a".repeat(64),
            "metadata-key",
            Instant.now().plusSeconds(900),
            Instant.now());
    when(claims.requireClaimedForUpdate("mbopi_1234567890abcdef"))
        .thenReturn(
            new ClaimedItem(
                "mbopi_1234567890abcdef",
                operation.getBatchOperationId(),
                "tenant-a",
                "ses_1234567890abcdef",
                1,
                "EXECUTING",
                "worker"));
    when(operations.findByBatchOperationIdAndTenantId(operation.getBatchOperationId(), "tenant-a"))
        .thenReturn(Optional.of(operation));

    service.executeClaimed("mbopi_1234567890abcdef");

    verify(tags)
        .assignForMetadataBatch(
            "tenant-a",
            "operator-a",
            "tag_1234567890abcdef",
            "ses_1234567890abcdef",
            operation.getBatchOperationId(),
            "metadata-batch:mbopi_1234567890abcdef");
    verify(tags)
        .assignForMetadataBatch(
            "tenant-a",
            "operator-a",
            "tag_fedcba0987654321",
            "ses_1234567890abcdef",
            operation.getBatchOperationId(),
            "metadata-batch:mbopi_1234567890abcdef");
    verify(claims)
        .commit(
            eq("mbopi_1234567890abcdef"), eq(operation.getBatchOperationId()), any(Instant.class));
  }

  private static WorkspaceMetadataBatchOperationEntity entity(NewOperation value) {
    return new WorkspaceMetadataBatchOperationEntity(
        value.batchOperationId(),
        value.tenantId(),
        value.actorId(),
        ASSIGN_GROUP,
        value.selector(),
        value.targetGroupId(),
        List.of(),
        value.reason(),
        value.requestHash(),
        value.idempotencyKey(),
        value.deadlineAt(),
        value.now());
  }

  private static SessionEntity session(String id) {
    var session = new SessionEntity();
    session.setId(id);
    session.setTenantId("tenant-a");
    return session;
  }
}
