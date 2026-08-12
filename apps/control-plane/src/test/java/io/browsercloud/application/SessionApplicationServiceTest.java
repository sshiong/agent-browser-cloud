package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.browsercloud.api.RemoteDesktopConnectionResponse;
import io.browsercloud.api.WorkspaceTagModels.WorkspaceTagSummary;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.coordinator.SessionDescriptor;
import io.browsercloud.coordinator.SessionListFilter;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.agent.AgentPolicy;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.operation.OwnerType;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  @Mock private TenantRouteApplicationService tenantRouteService;

  private SessionApplicationService service;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient()
        .when(proxyApplicationService.ensureBinding(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
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
            workspaceSettingsService,
            tenantRouteService);
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
                context,
                "local",
                "Integration browser",
                "grp_test",
                true,
                AgentPolicy.BALANCED,
                java.util.List.of("automation.extension")));
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
    assertThat(view.extensionIds()).containsExactly("automation.extension");
    assertThat(view.region()).isEqualTo("local");
    assertThat(view.resourceTemplate()).isEqualTo("standard-v1");
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
            new SessionDescriptor(
                context,
                "local",
                "Browser",
                null,
                false,
                AgentPolicy.BALANCED,
                java.util.List.of()));

    assertThatThrownBy(() -> service.requestTakeover("ses_test", "tenant-test", "operator-test"))
        .isInstanceOf(SessionApplicationService.HumanTakeoverDisabledException.class);
    verifyNoInteractions(coordinator);
  }

  @Test
  void issuesCollaborativeDesktopTicketWithoutReplacingActiveOperation() {
    var now = Instant.parse("2026-08-10T00:00:00Z");
    var context =
        new SessionContext(
            "ses_test",
            "tenant-test",
            "profile-test",
            "node-test",
            "runtime-test",
            "isolation-test",
            "proxy-test",
            2,
            4,
            8,
            1,
            ResourceClass.L3,
            SessionState.RUNNING,
            "policy-hash",
            now,
            now);
    var activeAgentOperation =
        new ExclusiveOperation(
            "op_agent",
            "ses_test",
            OwnerType.AGENT,
            "agent-worker",
            OperationMode.AGENT_INTERACTIVE,
            50,
            2,
            4,
            9,
            null,
            true,
            true,
            OperationPhase.EXECUTING,
            OperationState.ACTIVE,
            Set.of("browser.action"),
            now.plusSeconds(3600),
            now,
            null);
    var response =
        new RemoteDesktopConnectionResponse(
            "rdc_1234567890abcdefghij",
            "/desktop/v1/sessions/ses_test?ticket=test",
            now.plusSeconds(45),
            "rfb",
            4,
            false);
    when(sessionRepository.require("ses_test")).thenReturn(context);
    when(operationRepository.findActive("ses_test")).thenReturn(Optional.of(activeAgentOperation));
    when(remoteDesktopTicketService.issueCollaborative(
            eq("tenant-test"), eq("ses_test"), eq("operator-test"), eq(context)))
        .thenReturn(response);

    assertThat(service.createDesktopConnection("ses_test", "tenant-test", "operator-test"))
        .isEqualTo(response);
    verify(operationRepository).findActive("ses_test");
    verifyNoInteractions(coordinator);
  }

  @Test
  void preservesExplicitHumanTakeoverTicketAndActorBoundary() {
    var now = Instant.parse("2026-08-10T00:00:00Z");
    var context = runningContext(now);
    var takeover =
        new ExclusiveOperation(
            "op_takeover",
            "ses_test",
            OwnerType.HUMAN,
            "operator-test",
            OperationMode.HUMAN_TAKEOVER,
            90,
            2,
            4,
            7,
            null,
            true,
            false,
            OperationPhase.EXECUTING,
            OperationState.ACTIVE,
            Set.of("desktop.control"),
            now.plusSeconds(3600),
            now,
            null);
    var response =
        new RemoteDesktopConnectionResponse(
            "rdc_1234567890abcdefghij",
            "/desktop/v1/sessions/ses_test?ticket=exclusive",
            now.plusSeconds(45),
            "rfb",
            7,
            false);
    when(sessionRepository.require("ses_test")).thenReturn(context);
    when(operationRepository.findActive("ses_test")).thenReturn(Optional.of(takeover));
    when(remoteDesktopTicketService.issueExclusive(
            "tenant-test", "ses_test", "operator-test", takeover))
        .thenReturn(response);

    assertThat(service.createDesktopConnection("ses_test", "tenant-test", "operator-test"))
        .isEqualTo(response);
  }

  @Test
  void issuesServerEnforcedViewOnlyCollaborativeTicketWithoutCreatingAnOperation() {
    var now = Instant.parse("2026-08-10T00:00:00Z");
    var context = runningContext(now);
    var response =
        new RemoteDesktopConnectionResponse(
            "rdc_1234567890abcdefghij",
            "/desktop/v1/sessions/ses_test?ticket=view-only",
            now.plusSeconds(45),
            "rfb",
            4,
            true);
    when(sessionRepository.require("ses_test")).thenReturn(context);
    when(operationRepository.findActive("ses_test")).thenReturn(Optional.empty());
    when(remoteDesktopTicketService.issueCollaborative(
            "tenant-test", "ses_test", "viewer-test", context, true))
        .thenReturn(response);

    assertThat(service.createDesktopConnection("ses_test", "tenant-test", "viewer-test", true))
        .isEqualTo(response);
    verifyNoInteractions(coordinator);
  }

  private SessionContext runningContext(Instant now) {
    return new SessionContext(
        "ses_test",
        "tenant-test",
        "profile-test",
        "node-test",
        "runtime-test",
        "isolation-test",
        "proxy-test",
        2,
        4,
        8,
        1,
        ResourceClass.L3,
        SessionState.RUNNING,
        "policy-hash",
        now,
        now);
  }

  @Test
  void listsSessionsWithBatchOperationTagAndProxyProjections() {
    var now = Instant.parse("2026-07-23T00:00:00Z");
    var first = descriptor("ses_first", "First", now);
    var second = descriptor("ses_second", "Second", now.minusSeconds(30));
    var sessionIds = List.of("ses_first", "ses_second");
    when(sessionRepository.listByTenant("tenant-test", null, "", SessionListFilter.empty(), 100, 0))
        .thenReturn(List.of(first, second));
    when(sessionRepository.countByTenant("tenant-test", null, "", SessionListFilter.empty()))
        .thenReturn(2L);
    when(operationRepository.findActiveBySessionIds(sessionIds)).thenReturn(Map.of());
    when(workspaceTagService.summariesForSessions("tenant-test", sessionIds))
        .thenReturn(
            Map.of(
                "ses_first",
                List.of(new WorkspaceTagSummary("tag_first", "Production", "#35D6BE")),
                "ses_second",
                List.of()));
    var routingDecision =
        new io.browsercloud.api.ProxyBindingModels.ProxyRoutingDecision(
            "ses_first",
            "binding-first",
            "provider-first",
            "EXPLICIT",
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            List.of(),
            now);
    when(proxyApplicationService.assignedRoutingDecisions(sessionIds, "tenant-test"))
        .thenReturn(Map.of("ses_first", routingDecision));

    var result = service.list("tenant-test", null, "", 200, -1);

    assertThat(result.total()).isEqualTo(2);
    assertThat(result.limit()).isEqualTo(100);
    assertThat(result.offset()).isZero();
    assertThat(result.items().getFirst().tags()).extracting("tagId").containsExactly("tag_first");
    assertThat(result.items().getFirst().proxyBindingProfileId()).isEqualTo("binding-first");
    assertThat(result.items().getFirst().proxyRoutingDecision()).isEqualTo(routingDecision);
    assertThat(result.items().get(1).tags()).isEmpty();
    verify(operationRepository, never()).findActive(org.mockito.ArgumentMatchers.anyString());
    verify(workspaceTagService, never())
        .summariesForSession(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    verify(proxyApplicationService, never())
        .assignedRoutingDecision(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  private SessionDescriptor descriptor(String sessionId, String displayName, Instant updatedAt) {
    var context =
        new SessionContext(
            sessionId,
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
            SessionState.CREATED,
            "",
            updatedAt,
            updatedAt);
    return new SessionDescriptor(
        context, "local", displayName, null, true, AgentPolicy.BALANCED, List.of());
  }
}
