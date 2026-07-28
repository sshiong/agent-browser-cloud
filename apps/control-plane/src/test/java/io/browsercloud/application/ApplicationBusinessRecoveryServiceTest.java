package io.browsercloud.application;

import static io.browsercloud.api.BusinessRecoveryModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplicationBusinessRecoveryServiceTest {

  private static final String SESSION_ID = "ses_1234567890abcdef";
  private static final String TENANT_ID = "tenant-a";
  private static final String EXTENSION_ID = "jdgnleokimdbblcflcfcohbinohmmmlb";
  private static final Instant NOW = Instant.parse("2026-07-28T01:00:00Z");

  @Mock private ApplicationRecoveryContractJpaRepository contracts;
  @Mock private SessionApplicationBindingJpaRepository bindings;
  @Mock private BusinessRecoveryValidationJpaRepository validations;
  @Mock private SessionRepository sessions;
  @Mock private BrowserStateRepository browserStates;
  @Mock private BrowserCapacityApplicationService capacity;
  @Mock private BusinessRecoveryValidator defaultValidator;
  @Mock private IdempotencyService idempotency;

  private ObjectMapper objectMapper;
  private ApplicationBusinessRecoveryService service;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    service =
        new ApplicationBusinessRecoveryService(
            contracts,
            bindings,
            validations,
            sessions,
            browserStates,
            capacity,
            defaultValidator,
            idempotency,
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
                    SESSION_ID, TENANT_ID, "crm", contract.getContractId(), NOW)));
    when(contracts.findById(contract.getContractId())).thenReturn(Optional.of(contract));
    when(validations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
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
