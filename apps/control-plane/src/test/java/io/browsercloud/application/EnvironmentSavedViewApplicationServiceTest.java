package io.browsercloud.application;

import static io.browsercloud.api.EnvironmentSavedViewModels.EnvironmentPrimaryView.RUNNING;
import static io.browsercloud.api.EnvironmentSavedViewModels.SavedViewScope.PERSONAL;
import static io.browsercloud.api.EnvironmentSavedViewModels.SavedViewScope.WORKSPACE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.EnvironmentSavedViewModels.CreateEnvironmentSavedViewRequest;
import io.browsercloud.api.EnvironmentSavedViewModels.UpdateEnvironmentSavedViewRequest;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.EnvironmentSavedViewEntity;
import io.browsercloud.persistence.EnvironmentSavedViewJpaRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class EnvironmentSavedViewApplicationServiceTest {

  @Mock private EnvironmentSavedViewJpaRepository views;
  @Mock private IdempotencyService idempotency;
  @Mock private AuditApplicationService audit;

  private EnvironmentSavedViewApplicationService service;

  @BeforeEach
  void setUp() {
    service = new EnvironmentSavedViewApplicationService(views, idempotency, audit);
  }

  @Test
  void createsNormalizedPersonalViewWithIdempotencyAndAudit() {
    var request =
        new CreateEnvironmentSavedViewRequest(
            " Running CRM ",
            PERSONAL,
            RUNNING,
            SessionState.RUNNING,
            "  singapore  ",
            true,
            false,
            true);
    when(idempotency.claimEnvironmentSavedViewCreate(
            eq("tenant-a"), eq("operator-a"), eq("idem-a"), eq(request), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(4));
    when(views.saveAndFlush(any(EnvironmentSavedViewEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result =
        service.create(
            "tenant-a", "operator-a", Set.of("TENANT_OPERATOR"), "idem-a", "request-a", request);

    assertThat(result.savedViewId()).startsWith("svw_");
    assertThat(result.name()).isEqualTo("Running CRM");
    assertThat(result.searchQuery()).isEqualTo("singapore");
    assertThat(result.scope()).isEqualTo(PERSONAL);
    assertThat(result.showContextColumn()).isFalse();
    verify(audit).append(any());
  }

  @Test
  void requiresAdministratorForWorkspaceScope() {
    var request =
        new CreateEnvironmentSavedViewRequest(
            "Operations", WORKSPACE, RUNNING, null, "", true, true, true);

    assertThatThrownBy(
            () ->
                service.create(
                    "tenant-a",
                    "operator-a",
                    Set.of("TENANT_OPERATOR"),
                    "idem-a",
                    "request-a",
                    request))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void hidesAnotherActorsPersonalViewFromMutation() {
    var entity =
        personalView(
            "svw_1234567890abcdef", "tenant-a", "owner-a", Instant.parse("2026-07-30T00:00:00Z"));
    var request =
        new UpdateEnvironmentSavedViewRequest(0, "Updated", RUNNING, null, "", true, true, true);
    when(idempotency.claimEnvironmentSavedViewMutation(
            eq("tenant-a"),
            eq(entity.getSavedViewId()),
            eq("operator-b"),
            eq("UPDATE"),
            eq("idem-a"),
            eq(request),
            anyString()))
        .thenAnswer(invocation -> invocation.getArgument(6));
    when(views.findBySavedViewIdAndTenantId(entity.getSavedViewId(), "tenant-a"))
        .thenReturn(Optional.of(entity));

    assertThatThrownBy(
            () ->
                service.update(
                    "tenant-a",
                    "operator-b",
                    Set.of("TENANT_OPERATOR"),
                    entity.getSavedViewId(),
                    "idem-a",
                    "request-a",
                    request))
        .isInstanceOf(
            EnvironmentSavedViewApplicationService.EnvironmentSavedViewNotFoundException.class);
  }

  @Test
  void rejectsStaleVersionWithoutOverwriting() {
    var entity =
        personalView(
            "svw_1234567890abcdef", "tenant-a", "owner-a", Instant.parse("2026-07-30T00:00:00Z"));
    var request =
        new UpdateEnvironmentSavedViewRequest(1, "Updated", RUNNING, null, "", true, true, true);
    when(idempotency.claimEnvironmentSavedViewMutation(
            eq("tenant-a"),
            eq(entity.getSavedViewId()),
            eq("owner-a"),
            eq("UPDATE"),
            eq("idem-a"),
            eq(request),
            anyString()))
        .thenAnswer(invocation -> invocation.getArgument(6));
    when(views.findBySavedViewIdAndTenantId(entity.getSavedViewId(), "tenant-a"))
        .thenReturn(Optional.of(entity));

    assertThatThrownBy(
            () ->
                service.update(
                    "tenant-a",
                    "owner-a",
                    Set.of("TENANT_OPERATOR"),
                    entity.getSavedViewId(),
                    "idem-a",
                    "request-a",
                    request))
        .isInstanceOf(
            EnvironmentSavedViewApplicationService.EnvironmentSavedViewRejectedException.class)
        .hasMessage("SAVED_VIEW_VERSION_MISMATCH");
  }

  @Test
  void mapsConcurrentDatabaseUpdateToStableVersionConflict() {
    var entity =
        personalView(
            "svw_1234567890abcdef", "tenant-a", "owner-a", Instant.parse("2026-07-30T00:00:00Z"));
    var request =
        new UpdateEnvironmentSavedViewRequest(0, "Updated", RUNNING, null, "", true, true, true);
    when(idempotency.claimEnvironmentSavedViewMutation(
            eq("tenant-a"),
            eq(entity.getSavedViewId()),
            eq("owner-a"),
            eq("UPDATE"),
            eq("idem-a"),
            eq(request),
            anyString()))
        .thenAnswer(invocation -> invocation.getArgument(6));
    when(views.findBySavedViewIdAndTenantId(entity.getSavedViewId(), "tenant-a"))
        .thenReturn(Optional.of(entity));
    when(views.saveAndFlush(entity))
        .thenThrow(new OptimisticLockingFailureException("concurrent update"));

    assertThatThrownBy(
            () ->
                service.update(
                    "tenant-a",
                    "owner-a",
                    Set.of("TENANT_OPERATOR"),
                    entity.getSavedViewId(),
                    "idem-a",
                    "request-a",
                    request))
        .isInstanceOf(
            EnvironmentSavedViewApplicationService.EnvironmentSavedViewRejectedException.class)
        .hasMessage("SAVED_VIEW_VERSION_MISMATCH");
  }

  private static EnvironmentSavedViewEntity personalView(
      String id, String tenantId, String owner, Instant now) {
    return new EnvironmentSavedViewEntity(
        id, tenantId, owner, PERSONAL, "Saved", RUNNING, null, "", true, true, true, now);
  }
}
