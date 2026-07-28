package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.WorkspaceTagModels.WorkspaceTagRequest;
import io.browsercloud.persistence.SessionEntity;
import io.browsercloud.persistence.SessionJpaRepository;
import io.browsercloud.persistence.SessionTagAssignmentJpaRepository;
import io.browsercloud.persistence.WorkspaceTagEntity;
import io.browsercloud.persistence.WorkspaceTagJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceTagApplicationServiceTest {

  private static final String TENANT_ID = "tenant-test";
  private static final String SESSION_ID = "ses_1234567890abcdef";

  @Mock private WorkspaceTagJpaRepository tags;
  @Mock private SessionTagAssignmentJpaRepository assignments;
  @Mock private SessionJpaRepository sessions;
  @Mock private IdempotencyService idempotency;
  @Mock private AuditApplicationService audit;

  private WorkspaceTagApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new WorkspaceTagApplicationService(
            tags,
            assignments,
            sessions,
            idempotency,
            audit,
            new ObjectMapper().findAndRegisterModules());
  }

  @Test
  void createsNormalizedTenantTagWithIdempotentAuthority() {
    var request = new WorkspaceTagRequest(" Production ", " Critical workloads ", "#35d6be");
    when(idempotency.claimWorkspaceTagCreate(
            org.mockito.ArgumentMatchers.eq(TENANT_ID),
            org.mockito.ArgumentMatchers.eq("idem-create"),
            org.mockito.ArgumentMatchers.eq(request),
            anyString()))
        .thenAnswer(invocation -> invocation.getArgument(3));
    when(tags.findAllByTenantIdOrderByUpdatedAtDesc(TENANT_ID)).thenReturn(List.of());
    when(tags.saveAndFlush(any(WorkspaceTagEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(assignments.findAllByTenantIdAndTagIdOrderByAssignedAtDesc(
            org.mockito.ArgumentMatchers.eq(TENANT_ID), anyString()))
        .thenReturn(List.of());
    when(sessions.findAllById(any())).thenReturn(List.of());

    var result = service.create(TENANT_ID, "admin-a", "idem-create", "request-a", request);

    assertThat(result.tagId()).startsWith("tag_");
    assertThat(result.name()).isEqualTo("Production");
    assertThat(result.description()).isEqualTo("Critical workloads");
    assertThat(result.color()).isEqualTo("#35D6BE");
    assertThat(result.sessionCount()).isZero();
    verify(audit).append(any());
  }

  @Test
  void assignsInitialTagsOnlyFromTheAuthenticatedTenant() {
    var now = Instant.parse("2026-07-28T10:00:00Z");
    var session =
        new SessionEntity(
            SESSION_ID,
            TENANT_ID,
            "profile-a",
            "singapore",
            "L2",
            "CREATED",
            "",
            "{\"displayName\":\"CRM\"}",
            now);
    var first =
        new WorkspaceTagEntity(
            "tag_1234567890abcdef", TENANT_ID, "Production", null, "#35D6BE", "admin-a", now);
    var second =
        new WorkspaceTagEntity(
            "tag_fedcba0987654321", TENANT_ID, "CRM", null, "#718096", "admin-a", now);
    when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(tags.findAllByTenantIdAndTagIdInOrderByNameAsc(
            TENANT_ID, List.of(first.getTagId(), second.getTagId())))
        .thenReturn(List.of(second, first));
    when(assignments.insertIfAbsent(
            anyString(),
            org.mockito.ArgumentMatchers.eq(TENANT_ID),
            org.mockito.ArgumentMatchers.eq(SESSION_ID),
            anyString(),
            org.mockito.ArgumentMatchers.eq("operator-a"),
            any(Instant.class)))
        .thenReturn(1);

    service.assignInitial(
        TENANT_ID,
        "operator-a",
        SESSION_ID,
        List.of(first.getTagId(), second.getTagId(), first.getTagId()),
        "request-a");

    verify(assignments)
        .insertIfAbsent(
            anyString(),
            org.mockito.ArgumentMatchers.eq(TENANT_ID),
            org.mockito.ArgumentMatchers.eq(SESSION_ID),
            org.mockito.ArgumentMatchers.eq(first.getTagId()),
            org.mockito.ArgumentMatchers.eq("operator-a"),
            any(Instant.class));
    verify(assignments)
        .insertIfAbsent(
            anyString(),
            org.mockito.ArgumentMatchers.eq(TENANT_ID),
            org.mockito.ArgumentMatchers.eq(SESSION_ID),
            org.mockito.ArgumentMatchers.eq(second.getTagId()),
            org.mockito.ArgumentMatchers.eq("operator-a"),
            any(Instant.class));
    verify(audit).append(any());
  }

  @Test
  void rejectsUnknownOrCrossTenantInitialTag() {
    var now = Instant.parse("2026-07-28T10:00:00Z");
    var session =
        new SessionEntity(
            SESSION_ID, TENANT_ID, "profile-a", "local", "L2", "CREATED", "", "{}", now);
    when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(tags.findAllByTenantIdAndTagIdInOrderByNameAsc(TENANT_ID, List.of("tag_cross1234567890")))
        .thenReturn(List.of());

    assertThatThrownBy(
            () ->
                service.assignInitial(
                    TENANT_ID,
                    "operator-a",
                    SESSION_ID,
                    List.of("tag_cross1234567890"),
                    "request-a"))
        .isInstanceOf(WorkspaceTagApplicationService.WorkspaceTagNotFoundException.class);
  }
}
