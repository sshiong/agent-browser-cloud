package io.browsercloud.application;

import static io.browsercloud.api.EnvironmentImportModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.CreateSessionResponse;
import io.browsercloud.persistence.EnvironmentImportItemEntity;
import io.browsercloud.persistence.EnvironmentImportItemJpaRepository;
import io.browsercloud.persistence.EnvironmentImportJobEntity;
import io.browsercloud.persistence.EnvironmentImportJobJpaRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnvironmentImportApplicationServiceTest {

  @Mock private EnvironmentImportJobJpaRepository jobs;
  @Mock private EnvironmentImportItemJpaRepository items;
  @Mock private IdempotencyService idempotency;
  @Mock private ProfileApplicationService profiles;
  @Mock private RuntimeBuildPolicy runtimes;
  @Mock private WorkspaceGroupApplicationService groups;
  @Mock private WorkspaceTagApplicationService tags;
  @Mock private WorkspaceSettingsApplicationService settings;
  @Mock private ApplicationBusinessRecoveryService recovery;
  @Mock private SessionApplicationService sessions;
  @Mock private AuditApplicationService audit;

  private ObjectMapper mapper;
  private EnvironmentImportApplicationService service;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper().findAndRegisterModules();
    service =
        new EnvironmentImportApplicationService(
            jobs,
            items,
            idempotency,
            mapper,
            profiles,
            runtimes,
            groups,
            tags,
            settings,
            recovery,
            sessions,
            audit);
  }

  @Test
  void previewPersistsDuplicateAsValidationErrorWithoutCreatingResources() {
    var specification =
        new EnvironmentImportSpec(
            "CRM Singapore",
            null,
            "profile-sg",
            null,
            null,
            null,
            List.of(),
            null,
            null,
            0,
            0,
            false,
            null,
            null,
            false,
            false,
            0,
            0,
            false,
            List.of());
    var request =
        new PreviewEnvironmentImportRequest(1, "CRM fleet", List.of(specification, specification));
    var savedItems = new ArrayList<EnvironmentImportItemEntity>();
    when(idempotency.claimEnvironmentImportPreview(
            eq("tenant-a"), eq("operator-a"), eq("preview-1"), eq(request), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(4));
    when(settings.resolve("tenant-a"))
        .thenReturn(
            new WorkspaceSettingsApplicationService.EffectiveWorkspaceSettings(
                "runtime-stable", "singapore", true));
    when(jobs.saveAndFlush(any(EnvironmentImportJobEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(items.saveAll(anyList()))
        .thenAnswer(
            invocation -> {
              savedItems.addAll(invocation.getArgument(0));
              return savedItems;
            });
    when(items.findAllByImportIdAndTenantIdOrderByItemIndexAsc(anyString(), eq("tenant-a")))
        .thenAnswer(ignored -> savedItems);

    var result =
        service.preview("tenant-a", "operator-a", false, "preview-1", "request-1", request);

    assertThat(result.state()).isEqualTo(EnvironmentImportState.INVALID);
    assertThat(result.readyCount()).isEqualTo(1);
    assertThat(result.items().get(1).validationErrors())
        .containsExactly("DUPLICATE_ENVIRONMENT_SPEC");
    verify(sessions, never()).create(any(), anyString(), anyString(), anyString(), anyBoolean());
    verify(audit).append(any());
  }

  @Test
  void commitPublishesRealSessionAndOperationIds() {
    var now = Instant.parse("2026-07-30T00:00:00Z");
    var specification =
        new EnvironmentImportSpec(
            "CRM Singapore",
            null,
            "profile-sg",
            null,
            null,
            null,
            List.of(),
            null,
            null,
            0,
            0,
            false,
            null,
            null,
            false,
            false,
            0,
            0,
            false,
            List.of());
    var importId = "imp_1234567890abcdef";
    var job =
        new EnvironmentImportJobEntity(
            importId,
            "tenant-a",
            "operator-a",
            "CRM fleet",
            1,
            "a".repeat(64),
            EnvironmentImportState.VALIDATED,
            1,
            1,
            now);
    var item =
        new EnvironmentImportItemEntity(
            "imi_1234567890abcdef",
            importId,
            "tenant-a",
            0,
            specification.displayName(),
            mapper.convertValue(specification, java.util.Map.class),
            "b".repeat(64),
            List.of(),
            now);
    var request = new CommitEnvironmentImportRequest(0);
    when(idempotency.claimEnvironmentImportCommit(
            eq("tenant-a"),
            eq("operator-a"),
            eq(importId),
            eq("commit-1"),
            eq(request),
            anyString()))
        .thenAnswer(invocation -> invocation.getArgument(5));
    when(jobs.findOwnedForUpdate(importId, "tenant-a", "operator-a")).thenReturn(Optional.of(job));
    when(items.findAllByImportIdAndTenantIdOrderByItemIndexAsc(importId, "tenant-a"))
        .thenReturn(List.of(item));
    when(jobs.saveAndFlush(any(EnvironmentImportJobEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(sessions.create(any(), anyString(), eq("operator-a"), anyString(), eq(false)))
        .thenReturn(
            new CreateSessionResponse(
                "ses_1234567890abcdef", "op_1234567890abcdef", "CREATED", null, null));

    var result =
        service.commit("tenant-a", "operator-a", false, importId, "commit-1", "request-1", request);

    assertThat(result.state()).isEqualTo(EnvironmentImportState.COMMITTED);
    assertThat(result.succeededCount()).isEqualTo(1);
    assertThat(result.items().getFirst().sessionId()).isEqualTo("ses_1234567890abcdef");
    assertThat(result.items().getFirst().operationId()).isEqualTo("op_1234567890abcdef");
    verify(audit).append(any());
  }
}
