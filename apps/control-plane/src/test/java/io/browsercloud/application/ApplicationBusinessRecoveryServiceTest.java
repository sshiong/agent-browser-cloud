package io.browsercloud.application;

import static io.browsercloud.api.BusinessRecoveryModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplicationBusinessRecoveryServiceTest {

  private static final String SESSION_ID = "ses_1234567890abcdef";
  private static final String TENANT_ID = "tenant-a";
  private static final String EXTENSION_ID = "jdgnleokimdbblcflcfcohbinohmmmlb";
  private static final Instant NOW = Instant.parse("2026-07-28T01:00:00Z");

  @Mock private ApplicationRecoveryContractJpaRepository contracts;
  @Mock private ApplicationRecoveryContractRevisionJpaRepository revisions;
  @Mock private ApplicationRecoveryContractApprovalJpaRepository approvals;
  @Mock private SessionApplicationBindingJpaRepository bindings;
  @Mock private SessionApplicationRebindJpaRepository rebinds;
  @Mock private BusinessRecoveryValidationJpaRepository validations;
  @Mock private SessionRepository sessions;
  @Mock private OperationRepository operations;
  @Mock private BrowserStateRepository browserStates;
  @Mock private BrowserCapacityApplicationService capacity;
  @Mock private BusinessRecoveryValidator defaultValidator;
  @Mock private IdempotencyService idempotency;
  @Mock private AuditApplicationService audit;

  private ObjectMapper objectMapper;
  private ApplicationBusinessRecoveryService service;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    service =
        new ApplicationBusinessRecoveryService(
            contracts,
            revisions,
            approvals,
            bindings,
            rebinds,
            validations,
            sessions,
            operations,
            browserStates,
            capacity,
            defaultValidator,
            idempotency,
            audit,
            objectMapper);
  }

  @Test
  void createsNormalizedBoundedRecoveryContract() {
    when(contracts.findForUpdate(TENANT_ID, "crm")).thenReturn(Optional.empty());
    when(contracts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result =
        service.upsertContract(
            TENANT_ID,
            "crm",
            new UpsertRecoveryContractRequest(
                0,
                List.of("HTTPS://CRM.EXAMPLE.TEST:443/"),
                List.of("/customers"),
                List.of("/sign-in"),
                List.of(new TargetIndicator("BUTTON", "Continue")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                RecoveryAction.RELOAD,
                null,
                1,
                true),
            NOW);

    assertThat(result.version()).isEqualTo(1);
    assertThat(result.expectedOrigins()).containsExactly("https://crm.example.test");
    assertThat(result.requiredTargets()).containsExactly(new TargetIndicator("button", "Continue"));
  }

  @Test
  void acceptsOnlyExplicitRequiredChromiumExtensionAsRestartTarget() {
    when(contracts.findForUpdate(TENANT_ID, "crm")).thenReturn(Optional.empty());
    when(contracts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var request =
        new UpsertRecoveryContractRequest(
            0,
            List.of("https://crm.example.test"),
            List.of("/customers"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(EXTENSION_ID),
            false,
            RecoveryAction.RESTART_EXTENSION,
            EXTENSION_ID,
            1,
            true);

    var result = service.upsertContract(TENANT_ID, "crm", request, NOW);

    assertThat(result.recoveryAction()).isEqualTo(RecoveryAction.RESTART_EXTENSION);
    assertThat(result.recoveryExtensionId()).isEqualTo(EXTENSION_ID);
  }

  @Test
  void rejectsRestartTargetThatIsNotRequiredByTheContract() {
    var request =
        new UpsertRecoveryContractRequest(
            0,
            List.of("https://crm.example.test"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false,
            RecoveryAction.RESTART_EXTENSION,
            EXTENSION_ID,
            1,
            true);

    assertThatThrownBy(() -> service.upsertContract(TENANT_ID, "crm", request, NOW))
        .isInstanceOf(ApplicationBusinessRecoveryService.RecoveryContractRejectedException.class)
        .hasMessage("RECOVERY_EXTENSION_MUST_BE_REQUIRED_CHROMIUM_EXTENSION");
    verifyNoInteractions(contracts);
  }

  @Test
  void acceptsLegacyContractWithoutRecoveryActionButKeepsActionsDisabled() {
    when(contracts.findForUpdate(TENANT_ID, "crm")).thenReturn(Optional.empty());
    when(contracts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result =
        service.upsertContract(
            TENANT_ID,
            "crm",
            new UpsertRecoveryContractRequest(
                0,
                List.of("https://crm.example.test"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                null,
                1,
                true),
            NOW);

    assertThat(result.recoveryAction()).isEqualTo(RecoveryAction.NONE);
    assertThat(result.maximumAutoRecovery()).isEqualTo(1);
  }

  @Test
  void rejectsOriginsWithEmbeddedPathInsteadOfExecutingTenantLogic() {
    var request =
        new UpsertRecoveryContractRequest(
            0,
            List.of("https://crm.example.test/admin"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false,
            RecoveryAction.NONE,
            null,
            0,
            true);

    assertThatThrownBy(() -> service.upsertContract(TENANT_ID, "crm", request, NOW))
        .isInstanceOf(ApplicationBusinessRecoveryService.RecoveryContractRejectedException.class)
        .hasMessage("EXPECTED_ORIGIN_INVALID");
    verifyNoInteractions(contracts);
  }

  @Test
  void rejectsConflictingContractVersionButAllowsSemanticReplay() {
    var contract =
        contractUnchecked(
            List.of("https://crm.example.test"),
            List.of("/customers"),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    when(contracts.findForUpdate(TENANT_ID, "crm")).thenReturn(Optional.of(contract));

    var replay =
        new UpsertRecoveryContractRequest(
            0,
            List.of("https://crm.example.test"),
            List.of("/customers"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false,
            RecoveryAction.RELOAD,
            null,
            1,
            true);
    assertThat(service.upsertContract(TENANT_ID, "crm", replay, NOW).version()).isEqualTo(1);

    var conflict =
        new UpsertRecoveryContractRequest(
            0,
            List.of("https://crm.example.test"),
            List.of("/different"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false,
            RecoveryAction.RELOAD,
            null,
            1,
            true);
    assertThatThrownBy(() -> service.upsertContract(TENANT_ID, "crm", conflict, NOW))
        .isInstanceOf(
            ApplicationBusinessRecoveryService.RecoveryContractVersionConflictException.class);
    verify(contracts, never()).saveAndFlush(any());
  }

  @Test
  void applicationContractProducesDurableReadyVerdict() throws Exception {
    var contract =
        contract(
            List.of("https://crm.example.test"),
            List.of("/customers"),
            List.of("/sign-in"),
            List.of(new TargetIndicator("button", "Continue")),
            List.of());
    var state =
        state(
            "https://crm.example.test/customers/42",
            "COMPLETE",
            List.of(
                new NodeEvent.InteractiveTarget(
                    "target-1", "button", "Continue", null, true, true, false)));
    arrangeValidation(contract, state);

    var result =
        service.validateFromApi(SESSION_ID, TENANT_ID, "operator-a", "validate-1", "request-1");

    assertThat(result.verdict()).isEqualTo(Verdict.READY);
    assertThat(result.ready()).isTrue();
    assertThat(result.evidence()).containsExactly("APPLICATION_CONTRACT_SATISFIED");
    verify(validations).save(any(BusinessRecoveryValidationEntity.class));
  }

  @Test
  void loginIndicatorWinsAndKeepsAgentPaused() throws Exception {
    var contract =
        contract(
            List.of("https://crm.example.test"),
            List.of("/"),
            List.of("/sign-in"),
            List.of(),
            List.of(new TargetIndicator("textbox", "Email")));
    var state =
        state(
            "https://crm.example.test/sign-in",
            "COMPLETE",
            List.of(
                new NodeEvent.InteractiveTarget(
                    "target-login", "textbox", "Email", null, true, true, false)));
    arrangeValidation(contract, state);

    var result =
        service.validateFromApi(
            SESSION_ID, TENANT_ID, "operator-a", "validate-login", "request-login");

    assertThat(result.verdict()).isEqualTo(Verdict.LOGIN_REQUIRED);
    assertThat(result.ready()).isFalse();
    assertThat(result.evidence()).containsExactly("LOGIN_INDICATOR_MATCHED");
  }

  @Test
  void unnamedBrowserTargetsDoNotCrashContractEvaluation() throws Exception {
    var contract =
        contract(
            List.of("https://crm.example.test"),
            List.of("/customers"),
            List.of(),
            List.of(new TargetIndicator("button", "Continue")),
            List.of());
    var state =
        state(
            "https://crm.example.test/customers",
            "COMPLETE",
            List.of(
                new NodeEvent.InteractiveTarget(
                    "target-unnamed", "generic", null, null, true, false, false),
                new NodeEvent.InteractiveTarget(
                    "target-ready", "button", "Continue", null, true, true, false)));
    arrangeValidation(contract, state);

    var result =
        service.validateFromApi(
            SESSION_ID, TENANT_ID, "operator-a", "validate-null-name", "request-null-name");

    assertThat(result.verdict()).isEqualTo(Verdict.READY);
    assertThat(result.ready()).isTrue();
  }

  @Test
  void requiredExtensionEvidenceFailsClosedWhenPlacementIsUnavailable() throws Exception {
    var contract =
        contract(
            List.of("https://crm.example.test"),
            List.of("/customers"),
            List.of(),
            List.of(),
            List.of(),
            List.of("required.extension"));
    arrangeValidation(contract, state("https://crm.example.test/customers", "COMPLETE", List.of()));
    when(capacity.getPlacement(SESSION_ID, TENANT_ID))
        .thenThrow(new IllegalStateException("placement unavailable"));

    var result =
        service.validateFromApi(
            SESSION_ID, TENANT_ID, "operator-a", "validate-extension", "request-extension");

    assertThat(result.verdict()).isEqualTo(Verdict.MANUAL_RECOVERY_REQUIRED);
    assertThat(result.ready()).isFalse();
    assertThat(result.evidence()).containsExactly("PLACEMENT_EVIDENCE_UNAVAILABLE");
  }

  @Test
  void rejectsStateFromAnOldContextEpoch() throws Exception {
    when(sessions.require(SESSION_ID)).thenReturn(session());
    when(idempotency.claimBusinessRecoveryValidation(any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(4));
    when(browserStates.find(SESSION_ID))
        .thenReturn(
            Optional.of(
                new BrowserStateRepository.Snapshot(
                    TENANT_ID,
                    6,
                    state("https://crm.example.test/customers", "COMPLETE", List.of()))));

    assertThatThrownBy(
            () ->
                service.validateFromApi(
                    SESSION_ID, TENANT_ID, "operator-a", "validate-stale", "request-stale"))
        .isInstanceOf(
            ApplicationBusinessRecoveryService.BusinessRecoveryStateUnavailableException.class);
    verifyNoInteractions(validations);
  }

  @Test
  void bindsOnlyTheExactApprovedContractVersion() throws Exception {
    var contract =
        contract(
            List.of("https://crm.example.test"),
            List.of("/customers"),
            List.of(),
            List.of(),
            List.of());
    when(contracts.findByTenantIdAndApplicationId(TENANT_ID, "crm"))
        .thenReturn(Optional.of(contract));

    assertThatThrownBy(() -> service.bind(SESSION_ID, TENANT_ID, "crm", NOW))
        .isInstanceOf(
            ApplicationBusinessRecoveryService.RecoveryContractApprovalRequiredException.class);
    verify(bindings, never()).save(any());

    when(approvals.existsByTenantIdAndContractIdAndContractVersionAndState(
            TENANT_ID, contract.getContractId(), contract.getVersion(), "APPROVED"))
        .thenReturn(true);
    service.bind(SESSION_ID, TENANT_ID, "crm", NOW);

    var binding = ArgumentCaptor.forClass(SessionApplicationBindingEntity.class);
    verify(bindings).save(binding.capture());
    assertThat(binding.getValue().getContractVersion()).isEqualTo(contract.getVersion());
  }

  @Test
  void requiresASecondAdministratorToApproveTheExactCurrentVersion() throws Exception {
    var contract =
        contract(
            List.of("https://crm.example.test"),
            List.of("/customers"),
            List.of(),
            List.of(),
            List.of());
    when(contracts.findForUpdate(TENANT_ID, "crm")).thenReturn(Optional.of(contract));
    when(approvals.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var requested =
        service.requestApproval(
            TENANT_ID,
            "crm",
            new RequestRecoveryContractApprovalRequest(contract.getVersion(), "Production gate"),
            "admin-a",
            "request-approval",
            NOW);
    assertThat(requested.state()).isEqualTo(RecoveryContractApprovalState.REQUESTED);
    assertThat(requested.requestedBy()).isEqualTo("admin-a");

    var entity = ArgumentCaptor.forClass(ApplicationRecoveryContractApprovalEntity.class);
    verify(approvals).saveAndFlush(entity.capture());
    when(approvals.findForUpdate(entity.getValue().getApprovalId(), TENANT_ID, "crm"))
        .thenReturn(Optional.of(entity.getValue()));

    assertThatThrownBy(
            () ->
                service.approve(
                    TENANT_ID,
                    "crm",
                    entity.getValue().getApprovalId(),
                    "admin-a",
                    "same-admin",
                    NOW))
        .isInstanceOf(
            ApplicationBusinessRecoveryService.RecoveryContractApprovalRejectedException.class)
        .hasMessage("REQUESTER_CANNOT_APPROVE");
    verify(audit).appendIndependent(any());

    var approved =
        service.approve(
            TENANT_ID,
            "crm",
            entity.getValue().getApprovalId(),
            "admin-b",
            "second-admin",
            NOW.plusSeconds(1));
    assertThat(approved.state()).isEqualTo(RecoveryContractApprovalState.APPROVED);
    assertThat(approved.approvedBy()).isEqualTo("admin-b");
    assertThat(approved.evidenceHash()).hasSize(64);
  }

  @Test
  void listsImmutableRevisionsWithTheirExactApprovalState() throws Exception {
    var head = latestHead("arc_1234567890abcdefghij", 2);
    var v1 =
        revision(
            contract(
                List.of("https://crm.example.test"),
                List.of("/customers"),
                List.of(),
                List.of(),
                List.of()));
    var v2 = revisionVersion(v1, 2, "[\"/customers\",\"/workspaces\"]");
    var approval =
        new ApplicationRecoveryContractApprovalEntity(
            "ara_1234567890abcdefghij",
            TENANT_ID,
            head.getContractId(),
            "crm",
            1,
            "Initial production policy",
            "admin-a",
            NOW);
    approval.approve("admin-b", "evidence", NOW.plusSeconds(1));
    when(contracts.findByTenantIdAndApplicationId(TENANT_ID, "crm")).thenReturn(Optional.of(head));
    when(approvals.findAllByTenantIdAndContractIdOrderByRequestedAtDesc(
            TENANT_ID, head.getContractId()))
        .thenReturn(List.of(approval));
    when(revisions.findAllByContractIdAndTenantIdAndApplicationIdOrderByContractVersionDesc(
            head.getContractId(), TENANT_ID, "crm"))
        .thenReturn(List.of(v2, v1));

    var result = service.listRevisions(TENANT_ID, "crm");

    assertThat(result.currentVersion()).isEqualTo(2);
    assertThat(result.items()).extracting(RecoveryContractView::version).containsExactly(2L, 1L);
    assertThat(result.items().get(0).approvalState())
        .isEqualTo(RecoveryContractApprovalState.DRAFT);
    assertThat(result.items().get(1).approvalState())
        .isEqualTo(RecoveryContractApprovalState.APPROVED);
  }

  @Test
  void returnsOnlyChangedFieldsBetweenTwoImmutableRevisions() throws Exception {
    var source =
        revision(
            contract(
                List.of("https://crm.example.test"),
                List.of("/customers"),
                List.of(),
                List.of(),
                List.of()));
    var target = revisionVersion(source, 2, "[\"/customers\",\"/workspaces\"]");
    var head = latestHead(source.getContractId(), 2);
    when(contracts.findByTenantIdAndApplicationId(TENANT_ID, "crm")).thenReturn(Optional.of(head));
    when(revisions.findByContractIdAndContractVersionAndTenantIdAndApplicationId(
            source.getContractId(), 1, TENANT_ID, "crm"))
        .thenReturn(Optional.of(source));
    when(revisions.findByContractIdAndContractVersionAndTenantIdAndApplicationId(
            source.getContractId(), 2, TENANT_ID, "crm"))
        .thenReturn(Optional.of(target));

    var result = service.diff(TENANT_ID, "crm", 1, 2);

    assertThat(result.total()).isEqualTo(1);
    assertThat(result.changes())
        .containsExactly(
            new RecoveryContractFieldChange(
                "readyRoutePrefixes",
                "MODIFIED",
                "[\"/customers\"]",
                "[\"/customers\",\"/workspaces\"]"));
  }

  @Test
  void restoresApprovedHistoryAsANewDraftWithoutMutatingTheSource() throws Exception {
    var head = latestHead("arc_1234567890abcdefghij", 2);
    var source =
        revision(
            contract(
                List.of("https://crm.example.test"),
                List.of("/customers"),
                List.of(),
                List.of(),
                List.of()));
    var restored = latestHead(head.getContractId(), 3);
    var sourceExpectedOrigins = source.getExpectedOrigins();
    var sourceReadyRoutes = source.getReadyRoutePrefixes();
    var sourceLoginRoutes = source.getLoginRoutePrefixes();
    var sourceRequiredTargets = source.getRequiredTargets();
    var sourceLoginTargets = source.getLoginTargets();
    var sourcePermissionTargets = source.getPermissionDeniedTargets();
    var sourceAccountTargets = source.getAccountMismatchTargets();
    var sourceExtensionIds = source.getRequiredExtensionIds();
    var sourceAction = source.getRecoveryAction();
    var sourceMaximumRecovery = source.getMaximumAutoRecovery();
    when(restored.getExpectedOrigins()).thenReturn(sourceExpectedOrigins);
    when(restored.getReadyRoutePrefixes()).thenReturn(sourceReadyRoutes);
    when(restored.getLoginRoutePrefixes()).thenReturn(sourceLoginRoutes);
    when(restored.getRequiredTargets()).thenReturn(sourceRequiredTargets);
    when(restored.getLoginTargets()).thenReturn(sourceLoginTargets);
    when(restored.getPermissionDeniedTargets()).thenReturn(sourcePermissionTargets);
    when(restored.getAccountMismatchTargets()).thenReturn(sourceAccountTargets);
    when(restored.getRequiredExtensionIds()).thenReturn(sourceExtensionIds);
    when(restored.getRecoveryAction()).thenReturn(sourceAction);
    when(restored.getMaximumAutoRecovery()).thenReturn(sourceMaximumRecovery);
    when(restored.getCreatedAt()).thenReturn(NOW.minusSeconds(3600));
    when(restored.getUpdatedAt()).thenReturn(NOW.plusSeconds(1));
    when(contracts.findByTenantIdAndApplicationId(TENANT_ID, "crm")).thenReturn(Optional.of(head));
    when(contracts.findForUpdate(TENANT_ID, "crm")).thenReturn(Optional.of(head));
    when(idempotency.claimRecoveryContractRestore(any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(4));
    when(revisions.findByContractIdAndContractVersionAndTenantIdAndApplicationId(
            head.getContractId(), 1, TENANT_ID, "crm"))
        .thenReturn(Optional.of(source));
    when(approvals.existsByTenantIdAndContractIdAndContractVersionAndState(
            TENANT_ID, head.getContractId(), 1, "APPROVED"))
        .thenReturn(true);
    when(contracts.saveAndFlush(head)).thenReturn(restored);

    var result =
        service.restoreRevision(
            TENANT_ID,
            "crm",
            new RestoreRecoveryContractRevisionRequest(2, 1, "Rollback after CRM regression"),
            "admin-a",
            "restore-1",
            "request-restore",
            NOW.plusSeconds(1));

    assertThat(result.version()).isEqualTo(3);
    assertThat(result.approvalState()).isEqualTo(RecoveryContractApprovalState.DRAFT);
    verify(head)
        .update(
            source.getExpectedOrigins(),
            source.getReadyRoutePrefixes(),
            source.getLoginRoutePrefixes(),
            source.getRequiredTargets(),
            source.getLoginTargets(),
            source.getPermissionDeniedTargets(),
            source.getAccountMismatchTargets(),
            source.getRequiredExtensionIds(),
            source.isAllowDepthLimited(),
            source.getRecoveryAction(),
            source.getRecoveryExtensionId(),
            source.getMaximumAutoRecovery(),
            source.isEnabled(),
            NOW.plusSeconds(1));
    verifyNoInteractions(bindings);
    verify(audit)
        .append(
            argThat(
                record ->
                    record.action().equals("RECOVERY_CONTRACT_REVISION_RESTORED")
                        && record.details().get("sourceContractVersion").equals(1L)
                        && record.details().get("newContractVersion").equals(3L)));
  }

  @Test
  void replaysRestoreFromThePersistedRevisionId() throws Exception {
    var head = latestHead("arc_1234567890abcdefghij", 3);
    var restoredRevision =
        revisionVersion(
            revision(
                contractUnchecked(
                    List.of("https://crm.example.test"),
                    List.of("/customers"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of())),
            3,
            "[\"/customers\"]");
    var claimed = new AtomicReference<String>();
    when(contracts.findByTenantIdAndApplicationId(TENANT_ID, "crm")).thenReturn(Optional.of(head));
    when(idempotency.claimRecoveryContractRestore(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              if (claimed.get() == null) {
                claimed.set(head.getContractId() + ":v3:restore_1234567890abcdefghij");
              }
              return claimed.get();
            });
    when(revisions.findByContractIdAndContractVersionAndTenantIdAndApplicationId(
            head.getContractId(), 3, TENANT_ID, "crm"))
        .thenReturn(Optional.of(restoredRevision));

    var result =
        service.restoreRevision(
            TENANT_ID,
            "crm",
            new RestoreRecoveryContractRevisionRequest(2, 1, "same request"),
            "admin-a",
            "restore-replay",
            "request-replay",
            NOW);

    assertThat(result.version()).isEqualTo(3);
    verify(contracts, never()).findForUpdate(any(), any());
    verify(contracts, never()).saveAndFlush(any());
  }

  @Test
  void evaluatesTheImmutableBoundRevisionAfterThePublishedHeadAdvances() throws Exception {
    var revisionSource =
        contract(
            List.of("https://crm.example.test"),
            List.of("/customers"),
            List.of(),
            List.of(new TargetIndicator("button", "Continue")),
            List.of());
    var head = latestHead(revisionSource.getContractId(), 2);
    when(sessions.require(SESSION_ID)).thenReturn(session());
    when(idempotency.claimBusinessRecoveryValidation(any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(4));
    when(browserStates.find(SESSION_ID))
        .thenReturn(
            Optional.of(
                new BrowserStateRepository.Snapshot(
                    TENANT_ID,
                    7,
                    state(
                        "https://crm.example.test/customers",
                        "COMPLETE",
                        List.of(
                            new NodeEvent.InteractiveTarget(
                                "target-1", "button", "Continue", null, true, true, false))))));
    when(bindings.findBySessionIdAndTenantId(SESSION_ID, TENANT_ID))
        .thenReturn(
            Optional.of(
                new SessionApplicationBindingEntity(
                    SESSION_ID, TENANT_ID, "crm", revisionSource.getContractId(), 1, NOW)));
    when(contracts.findById(revisionSource.getContractId())).thenReturn(Optional.of(head));
    when(approvals.existsByTenantIdAndContractIdAndContractVersionAndState(
            TENANT_ID, revisionSource.getContractId(), 1, "APPROVED"))
        .thenReturn(true);
    var pinnedRevision = revision(revisionSource);
    when(revisions.findByContractIdAndContractVersionAndTenantIdAndApplicationId(
            revisionSource.getContractId(), 1, TENANT_ID, "crm"))
        .thenReturn(Optional.of(pinnedRevision));
    when(validations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result =
        service.validateFromApi(
            SESSION_ID, TENANT_ID, "operator-a", "validate-pinned", "request-pinned");

    assertThat(result.contractVersion()).isEqualTo(1);
    assertThat(result.ready()).isTrue();
    assertThat(result.evidence()).containsExactly("APPLICATION_CONTRACT_SATISFIED");
  }

  @Test
  void rebindsThroughAnIdempotentCommittedExclusiveOperation() {
    var head = latestHead("arc_1234567890abcdefghij", 2);
    var targetRevision =
        mock(ApplicationRecoveryContractRevisionEntity.class, withSettings().lenient());
    when(targetRevision.isEnabled()).thenReturn(true);
    var binding =
        new SessionApplicationBindingEntity(
            SESSION_ID, TENANT_ID, "crm", head.getContractId(), 1, NOW);
    when(idempotency.claimApplicationBindingRebind(any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(4));
    when(sessions.requireForUpdate(SESSION_ID)).thenReturn(session());
    when(bindings.findForUpdate(SESSION_ID, TENANT_ID)).thenReturn(Optional.of(binding));
    when(contracts.findForUpdate(TENANT_ID, "crm")).thenReturn(Optional.of(head));
    when(approvals.existsByTenantIdAndContractIdAndContractVersionAndState(
            TENANT_ID, head.getContractId(), 2, "APPROVED"))
        .thenReturn(true);
    when(revisions.findByContractIdAndContractVersionAndTenantIdAndApplicationId(
            head.getContractId(), 2, TENANT_ID, "crm"))
        .thenReturn(Optional.of(targetRevision));
    when(operations.nextOperationEpoch(SESSION_ID)).thenReturn(12L);
    when(rebinds.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result =
        service.rebind(
            SESSION_ID,
            TENANT_ID,
            "admin-a",
            "rebind-1",
            "request-rebind",
            new RebindSessionApplicationRequest(1, 2));

    assertThat(result.previousContractVersion()).isEqualTo(1);
    assertThat(result.targetContractVersion()).isEqualTo(2);
    assertThat(result.state()).isEqualTo("COMMITTED");
    assertThat(binding.getContractVersion()).isEqualTo(2);
    verify(operations)
        .insert(
            argThat(
                operation ->
                    operation.mode()
                            == io.browsercloud.domain.operation.OperationMode.APPLICATION_BINDING
                        && operation.state()
                            == io.browsercloud.domain.operation.OperationState.COMMITTED
                        && operation.operationEpoch() == 12));
    verify(audit).append(any());
  }

  @Test
  void rejectsApprovalWhenContractChangedAfterRequest() throws Exception {
    var contract =
        contract(
            List.of("https://crm.example.test"),
            List.of("/customers"),
            List.of(),
            List.of(),
            List.of());
    var approval =
        new ApplicationRecoveryContractApprovalEntity(
            "ara_1234567890abcdefghij",
            TENANT_ID,
            contract.getContractId(),
            "crm",
            contract.getVersion() - 1,
            "Review",
            "admin-a",
            NOW);
    when(approvals.findForUpdate(approval.getApprovalId(), TENANT_ID, "crm"))
        .thenReturn(Optional.of(approval));
    when(contracts.findForUpdate(TENANT_ID, "crm")).thenReturn(Optional.of(contract));

    assertThatThrownBy(
            () ->
                service.approve(
                    TENANT_ID,
                    "crm",
                    approval.getApprovalId(),
                    "admin-b",
                    "stale-approval",
                    NOW.plusSeconds(1)))
        .isInstanceOf(
            ApplicationBusinessRecoveryService.RecoveryContractApprovalRejectedException.class)
        .hasMessage("CONTRACT_VERSION_CHANGED");
    verify(audit).appendIndependent(any());
  }

  private void arrangeValidation(
      ApplicationRecoveryContractEntity contract, NodeEvent.StateUpdated state) {
    when(sessions.require(SESSION_ID)).thenReturn(session());
    when(idempotency.claimBusinessRecoveryValidation(any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(4));
    when(browserStates.find(SESSION_ID))
        .thenReturn(Optional.of(new BrowserStateRepository.Snapshot(TENANT_ID, 7, state)));
    when(bindings.findBySessionIdAndTenantId(SESSION_ID, TENANT_ID))
        .thenReturn(
            Optional.of(
                new SessionApplicationBindingEntity(
                    SESSION_ID,
                    TENANT_ID,
                    "crm",
                    contract.getContractId(),
                    contract.getVersion(),
                    NOW)));
    when(contracts.findById(contract.getContractId())).thenReturn(Optional.of(contract));
    var exactRevision = revision(contract);
    when(revisions.findByContractIdAndContractVersionAndTenantIdAndApplicationId(
            contract.getContractId(), contract.getVersion(), TENANT_ID, "crm"))
        .thenReturn(Optional.of(exactRevision));
    when(approvals.existsByTenantIdAndContractIdAndContractVersionAndState(
            TENANT_ID, contract.getContractId(), contract.getVersion(), "APPROVED"))
        .thenReturn(true);
    when(validations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  private ApplicationRecoveryContractRevisionEntity revision(
      ApplicationRecoveryContractEntity contract) {
    var revision = mock(ApplicationRecoveryContractRevisionEntity.class, withSettings().lenient());
    when(revision.getContractId()).thenReturn(contract.getContractId());
    when(revision.getContractVersion()).thenReturn(contract.getVersion());
    when(revision.getTenantId()).thenReturn(contract.getTenantId());
    when(revision.getApplicationId()).thenReturn(contract.getApplicationId());
    when(revision.getExpectedOrigins()).thenReturn(contract.getExpectedOrigins());
    when(revision.getReadyRoutePrefixes()).thenReturn(contract.getReadyRoutePrefixes());
    when(revision.getLoginRoutePrefixes()).thenReturn(contract.getLoginRoutePrefixes());
    when(revision.getRequiredTargets()).thenReturn(contract.getRequiredTargets());
    when(revision.getLoginTargets()).thenReturn(contract.getLoginTargets());
    when(revision.getPermissionDeniedTargets()).thenReturn(contract.getPermissionDeniedTargets());
    when(revision.getAccountMismatchTargets()).thenReturn(contract.getAccountMismatchTargets());
    when(revision.getRequiredExtensionIds()).thenReturn(contract.getRequiredExtensionIds());
    when(revision.isAllowDepthLimited()).thenReturn(contract.isAllowDepthLimited());
    when(revision.getRecoveryAction()).thenReturn(contract.getRecoveryAction());
    when(revision.getRecoveryExtensionId()).thenReturn(contract.getRecoveryExtensionId());
    when(revision.getMaximumAutoRecovery()).thenReturn(contract.getMaximumAutoRecovery());
    when(revision.isEnabled()).thenReturn(contract.isEnabled());
    return revision;
  }

  private ApplicationRecoveryContractRevisionEntity revisionVersion(
      ApplicationRecoveryContractRevisionEntity source, long version, String readyRoutePrefixes) {
    var contractId = source.getContractId();
    var tenantId = source.getTenantId();
    var applicationId = source.getApplicationId();
    var expectedOrigins = source.getExpectedOrigins();
    var loginRoutePrefixes = source.getLoginRoutePrefixes();
    var requiredTargets = source.getRequiredTargets();
    var loginTargets = source.getLoginTargets();
    var permissionDeniedTargets = source.getPermissionDeniedTargets();
    var accountMismatchTargets = source.getAccountMismatchTargets();
    var requiredExtensionIds = source.getRequiredExtensionIds();
    var allowDepthLimited = source.isAllowDepthLimited();
    var recoveryAction = source.getRecoveryAction();
    var recoveryExtensionId = source.getRecoveryExtensionId();
    var maximumAutoRecovery = source.getMaximumAutoRecovery();
    var enabled = source.isEnabled();
    var revision = mock(ApplicationRecoveryContractRevisionEntity.class, withSettings().lenient());
    when(revision.getContractId()).thenReturn(contractId);
    when(revision.getContractVersion()).thenReturn(version);
    when(revision.getTenantId()).thenReturn(tenantId);
    when(revision.getApplicationId()).thenReturn(applicationId);
    when(revision.getExpectedOrigins()).thenReturn(expectedOrigins);
    when(revision.getReadyRoutePrefixes()).thenReturn(readyRoutePrefixes);
    when(revision.getLoginRoutePrefixes()).thenReturn(loginRoutePrefixes);
    when(revision.getRequiredTargets()).thenReturn(requiredTargets);
    when(revision.getLoginTargets()).thenReturn(loginTargets);
    when(revision.getPermissionDeniedTargets()).thenReturn(permissionDeniedTargets);
    when(revision.getAccountMismatchTargets()).thenReturn(accountMismatchTargets);
    when(revision.getRequiredExtensionIds()).thenReturn(requiredExtensionIds);
    when(revision.isAllowDepthLimited()).thenReturn(allowDepthLimited);
    when(revision.getRecoveryAction()).thenReturn(recoveryAction);
    when(revision.getRecoveryExtensionId()).thenReturn(recoveryExtensionId);
    when(revision.getMaximumAutoRecovery()).thenReturn(maximumAutoRecovery);
    when(revision.isEnabled()).thenReturn(enabled);
    when(revision.getContractCreatedAt()).thenReturn(NOW.minusSeconds(3600));
    when(revision.getPublishedAt()).thenReturn(NOW.plusSeconds(version));
    return revision;
  }

  private ApplicationRecoveryContractEntity latestHead(String contractId, long version) {
    var head = mock(ApplicationRecoveryContractEntity.class, withSettings().lenient());
    when(head.getContractId()).thenReturn(contractId);
    when(head.getTenantId()).thenReturn(TENANT_ID);
    when(head.getApplicationId()).thenReturn("crm");
    when(head.getVersion()).thenReturn(version);
    when(head.isEnabled()).thenReturn(true);
    return head;
  }

  private ApplicationRecoveryContractEntity contract(
      List<String> origins,
      List<String> readyRoutes,
      List<String> loginRoutes,
      List<TargetIndicator> requiredTargets,
      List<TargetIndicator> loginTargets)
      throws Exception {
    return contract(origins, readyRoutes, loginRoutes, requiredTargets, loginTargets, List.of());
  }

  private ApplicationRecoveryContractEntity contract(
      List<String> origins,
      List<String> readyRoutes,
      List<String> loginRoutes,
      List<TargetIndicator> requiredTargets,
      List<TargetIndicator> loginTargets,
      List<String> requiredExtensionIds)
      throws Exception {
    return new ApplicationRecoveryContractEntity(
        "arc_1234567890abcdefghij",
        TENANT_ID,
        "crm",
        objectMapper.writeValueAsString(origins),
        objectMapper.writeValueAsString(readyRoutes),
        objectMapper.writeValueAsString(loginRoutes),
        objectMapper.writeValueAsString(requiredTargets),
        objectMapper.writeValueAsString(loginTargets),
        "[]",
        "[]",
        objectMapper.writeValueAsString(requiredExtensionIds),
        false,
        RecoveryAction.RELOAD.name(),
        null,
        1,
        true,
        NOW);
  }

  private ApplicationRecoveryContractEntity contractUnchecked(
      List<String> origins,
      List<String> readyRoutes,
      List<String> loginRoutes,
      List<TargetIndicator> requiredTargets,
      List<TargetIndicator> loginTargets,
      List<String> requiredExtensionIds) {
    try {
      return contract(
          origins, readyRoutes, loginRoutes, requiredTargets, loginTargets, requiredExtensionIds);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static NodeEvent.StateUpdated state(
      String url, String quality, List<NodeEvent.InteractiveTarget> targets) {
    return new NodeEvent.StateUpdated(SESSION_ID, 12, 12, url, "CRM", "hash", quality, targets);
  }

  private static SessionContext session() {
    return new SessionContext(
        SESSION_ID,
        TENANT_ID,
        "profile-a",
        "node-a",
        "runtime-a",
        "isolation-a",
        "proxy-a",
        3,
        7,
        1,
        1,
        ResourceClass.L2,
        SessionState.RUNNING,
        "policy-hash",
        NOW,
        NOW);
  }
}
