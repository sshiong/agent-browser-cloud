package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.WorkspaceGroupModels.WorkspaceGroupRequest;
import io.browsercloud.domain.agent.AgentPolicy;
import io.browsercloud.domain.resource.MaximumReachedPolicy;
import io.browsercloud.persistence.SessionEntity;
import io.browsercloud.persistence.SessionJpaRepository;
import io.browsercloud.persistence.WorkspaceGroupEntity;
import io.browsercloud.persistence.WorkspaceGroupJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceGroupApplicationServiceTest {

  @Mock private WorkspaceGroupJpaRepository groups;
  @Mock private SessionJpaRepository sessions;
  @Mock private IdempotencyService idempotency;
  @Mock private AuditApplicationService audit;

  private WorkspaceGroupApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new WorkspaceGroupApplicationService(
            groups, sessions, idempotency, audit, new ObjectMapper());
  }

  @Test
  void createsAnIdempotentTenantGroupAndResolvesItsAutoDefaults() {
    var request =
        new WorkspaceGroupRequest(
            " Operations ",
            "Critical customer sessions",
            "#35d6be",
            MaximumReachedPolicy.PAUSE_AGENT,
            true,
            true);
    when(idempotency.claimWorkspaceGroupCreate(
            eq("tenant-test"), eq("idem-1"), eq(request), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(3));
    when(groups.save(any(WorkspaceGroupEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(sessions.findAllByTenantIdAndGroupIdOrderByCreatedAtDesc(eq("tenant-test"), anyString()))
        .thenReturn(List.of());

    var created = service.create("tenant-test", "user-test", "idem-1", "request-1", request, false);
    when(groups.findByGroupIdAndTenantId(created.groupId(), "tenant-test"))
        .thenReturn(
            Optional.of(
                new WorkspaceGroupEntity(
                    created.groupId(),
                    "tenant-test",
                    created.name(),
                    created.description(),
                    created.color(),
                    created.defaultOnMaximumReached(),
                    created.defaultAllowMigration(),
                    created.defaultAllowHibernate(),
                    "user-test",
                    Instant.parse("2026-07-28T00:00:00Z"))));

    var inherited = service.resolvePolicy("tenant-test", created.groupId(), null);

    assertThat(created.name()).isEqualTo("Operations");
    assertThat(created.color()).isEqualTo("#35D6BE");
    assertThat(inherited.mode().name()).isEqualTo("AUTO");
    assertThat(inherited.onMaximumReached()).isEqualTo(MaximumReachedPolicy.PAUSE_AGENT);
    assertThat(inherited.allowMigration()).isTrue();
  }

  @Test
  void assignsOnlyTenantSessionsAndRejectsStrictDefaultsWithoutPlatformAdmin() {
    var request =
        new WorkspaceGroupRequest(
            "Strict", null, "#FF0000", MaximumReachedPolicy.TERMINATE_STRICT, false, false);
    assertThatThrownBy(
            () ->
                service.create(
                    "tenant-test", "user-test", "idem-strict", "request-1", request, false))
        .isInstanceOf(WorkspaceGroupApplicationService.WorkspaceGroupRejectedException.class)
        .hasMessage("STRICT_POLICY_REQUIRES_PLATFORM_ADMIN");

    var group =
        new WorkspaceGroupEntity(
            "grp_1234567890abcdef",
            "tenant-test",
            "Operations",
            null,
            "#35D6BE",
            MaximumReachedPolicy.PAUSE_AGENT,
            true,
            true,
            "user-test",
            Instant.parse("2026-07-28T00:00:00Z"));
    var session =
        new SessionEntity(
            "ses_1234567890abcdef",
            "tenant-test",
            "profile-test",
            "local",
            "L2",
            "CREATED",
            "",
            "{\"displayName\":\"CRM\"}",
            true,
            AgentPolicy.BALANCED,
            Instant.parse("2026-07-28T00:00:00Z"));
    when(groups.findByGroupIdAndTenantId(group.getGroupId(), "tenant-test"))
        .thenReturn(Optional.of(group));
    when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
    when(idempotency.claimWorkspaceGroupMutation(
            eq("tenant-test"),
            eq(group.getGroupId()),
            anyString(),
            eq("idem-assign"),
            eq(session.getId()),
            anyString()))
        .thenAnswer(invocation -> invocation.getArgument(5));
    when(sessions.findAllByTenantIdAndGroupIdOrderByCreatedAtDesc(
            "tenant-test", group.getGroupId()))
        .thenReturn(List.of(session));

    var updated =
        service.assign(
            "tenant-test",
            "operator-test",
            group.getGroupId(),
            session.getId(),
            "idem-assign",
            "request-2");

    assertThat(session.getGroupId()).isEqualTo(group.getGroupId());
    assertThat(updated.sessions()).extracting("sessionId").containsExactly(session.getId());
    verify(sessions).save(session);
  }
}
