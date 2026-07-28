package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.coordinator.SessionDescriptor;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.agent.AgentPolicy;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionApplicationServiceTest {

  @Mock private SessionCoordinator coordinator;
  @Mock private SessionRepository sessionRepository;
  @Mock private OperationRepository operationRepository;
  @Mock private BrowserStateRepository browserStateRepository;
  @Mock private IdempotencyService idempotencyService;
  @Mock private RemoteDesktopTicketService remoteDesktopTicketService;
  @Mock private ProfileApplicationService profileApplicationService;
  @Mock private StaticProxyApplicationService proxyApplicationService;
  @Mock private AuditApplicationService auditService;
  @Mock private DurableWorkflowApplicationService workflowService;
  @Mock private RuntimeBuildPolicy runtimeBuildPolicy;
  @Mock private CapacityAdmissionService capacityAdmissionService;
  @Mock private BrowserCapacityApplicationService browserCapacityService;
  @Mock private SessionResourceApplicationService sessionResourceService;
  @Mock private ApplicationBusinessRecoveryService businessRecoveryService;
  @Mock private WorkspaceGroupApplicationService workspaceGroupService;
  @Mock private WorkspaceTagApplicationService workspaceTagService;
  @Mock private WorkspaceSettingsApplicationService workspaceSettingsService;

  private SessionApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new SessionApplicationService(
            coordinator,
            sessionRepository,
            operationRepository,
            browserStateRepository,
            idempotencyService,
            remoteDesktopTicketService,
            profileApplicationService,
            proxyApplicationService,
            auditService,
            workflowService,
            runtimeBuildPolicy,
            capacityAdmissionService,
            browserCapacityService,
            sessionResourceService,
            businessRecoveryService,
            workspaceGroupService,
            workspaceTagService,
            workspaceSettingsService);
  }

  @Test
  void shouldExposeControlledSessionDescriptorFields() {
    var now = Instant.parse("2026-07-23T00:00:00Z");
    var context =
        new SessionContext(
            "ses_test",
            "tenant-test",
            "profile-test",
            null,
            null,
            null,
            null,
            0,
            0,
            0,
            0,
            ResourceClass.L2,
            SessionState.CREATED,
            "",
            now,
            now);
    when(sessionRepository.describe("ses_test"))
        .thenReturn(
            new SessionDescriptor(
                context, "local", "Integration browser", "grp_test", true, AgentPolicy.BALANCED));
    when(operationRepository.findActive("ses_test")).thenReturn(Optional.empty());
    when(workspaceTagService.summariesForSession("tenant-test", "ses_test"))
        .thenReturn(java.util.List.of());

    var view = service.get("ses_test", "tenant-test");

    assertThat(view.displayName()).isEqualTo("Integration browser");
    assertThat(view.profileId()).isEqualTo("profile-test");
    assertThat(view.groupId()).isEqualTo("grp_test");
    assertThat(view.tags()).isEmpty();
    assertThat(view.humanTakeoverEnabled()).isTrue();
    assertThat(view.agentPolicy()).isEqualTo(AgentPolicy.BALANCED);
    assertThat(view.region()).isEqualTo("local");
    assertThat(view.resourceClass()).isEqualTo(ResourceClass.L2);
  }

  @Test
  void rejectsHumanTakeoverWhenItWasDisabledAtCreation() {
    var now = Instant.parse("2026-07-23T00:00:00Z");
    var context =
        new SessionContext(
            "ses_test",
            "tenant-test",
            "profile-test",
            null,
            "runtime-test",
            null,
            null,
            0,
            0,
            0,
            0,
            ResourceClass.L2,
            SessionState.RUNNING,
            "",
            now,
            now);
    when(sessionRepository.require("ses_test")).thenReturn(context);
    when(sessionRepository.describe("ses_test"))
        .thenReturn(
            new SessionDescriptor(context, "local", "Browser", null, false, AgentPolicy.BALANCED));

    assertThatThrownBy(() -> service.requestTakeover("ses_test", "tenant-test", "operator-test"))
        .isInstanceOf(SessionApplicationService.HumanTakeoverDisabledException.class);
    verifyNoInteractions(coordinator);
  }
}
