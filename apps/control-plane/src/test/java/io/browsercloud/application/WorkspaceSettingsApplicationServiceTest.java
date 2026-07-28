package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.WorkspaceSettingsModels.WorkspaceSettingsRequest;
import io.browsercloud.persistence.WorkspaceSettingsEntity;
import io.browsercloud.persistence.WorkspaceSettingsJpaRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceSettingsApplicationServiceTest {

  @Mock private WorkspaceSettingsJpaRepository repository;
  @Mock private RuntimeBuildPolicy runtimeBuildPolicy;
  @Mock private IdempotencyService idempotency;
  @Mock private AuditApplicationService audit;
  @Mock private WorkspaceSettingsEntity entity;

  private WorkspaceSettingsApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new WorkspaceSettingsApplicationService(
            repository, runtimeBuildPolicy, idempotency, audit, "runtime-system");
  }

  @Test
  void exposesDeclaredSystemDefaultsWithoutPretendingTheyArePersisted() {
    when(repository.findById("tenant-a")).thenReturn(Optional.empty());

    var result = service.get("tenant-a");

    assertThat(result.workspaceName()).isEqualTo("Default Workspace");
    assertThat(result.defaultRuntimeBuildId()).isEqualTo("runtime-system");
    assertThat(result.source()).isEqualTo("SYSTEM_DEFAULT");
    assertThat(result.updatedAt()).isNull();
  }

  @Test
  void persistsAnApprovedWorkspaceOverrideAndAuditsIt() {
    var request =
        new WorkspaceSettingsRequest(" Operations ", "runtime-stable", "singapore", false);
    when(idempotency.claimWorkspaceSettingsUpdate(anyString(), anyString(), any(), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(3));
    when(repository.findById("tenant-a")).thenReturn(Optional.of(entity));
    when(entity.getWorkspaceName()).thenReturn("Operations");
    when(entity.getDefaultRuntimeBuildId()).thenReturn("runtime-stable");
    when(entity.getDefaultRegion()).thenReturn("singapore");
    when(entity.isDefaultHumanTakeoverEnabled()).thenReturn(false);
    when(entity.getUpdatedBy()).thenReturn("admin-a");
    when(entity.getUpdatedAt()).thenReturn(Instant.parse("2026-07-28T00:00:00Z"));
    when(entity.getVersion()).thenReturn(0L);

    var result = service.update("tenant-a", "admin-a", "idem-a", "request-a", request);

    verify(runtimeBuildPolicy).requireApproved("runtime-stable");
    verify(repository)
        .upsert(
            eq("tenant-a"),
            eq("Operations"),
            eq("runtime-stable"),
            eq("singapore"),
            eq(false),
            eq("admin-a"),
            any(Instant.class));
    verify(audit).append(any());
    assertThat(result.source()).isEqualTo("WORKSPACE_OVERRIDE");
    assertThat(result.defaultHumanTakeoverEnabled()).isFalse();
  }
}
