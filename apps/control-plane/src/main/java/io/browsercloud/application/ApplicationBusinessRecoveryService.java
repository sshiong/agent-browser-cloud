package io.browsercloud.application;

import static io.browsercloud.api.BusinessRecoveryModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.OperationFactory;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.persistence.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant/application-aware Business Recovery authority.
 *
 * <p>The contract is a bounded declarative DSL. It deliberately supports only exact origins, route
 * prefixes and exact accessible target indicators; tenant JavaScript and regular expressions are
 * never evaluated in the Control Plane.
 */
@Service
public class ApplicationBusinessRecoveryService {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private static final TypeReference<List<TargetIndicator>> TARGET_LIST = new TypeReference<>() {};
  private static final TypeReference<List<ProviderEvidenceRequirement>> PROVIDER_REQUIREMENT_LIST =
      new TypeReference<>() {};

  private final ApplicationRecoveryContractJpaRepository contracts;
  private final ApplicationRecoveryContractRevisionJpaRepository revisions;
  private final ApplicationRecoveryContractApprovalJpaRepository approvals;
  private final SessionApplicationBindingJpaRepository bindings;
  private final SessionApplicationRebindJpaRepository rebinds;
  private final BusinessRecoveryValidationJpaRepository validations;
  private final BusinessRecoveryProviderEvidenceJpaRepository providerEvidence;
  private final SessionRepository sessions;
  private final OperationRepository operations;
  private final BrowserStateRepository browserStates;
  private final BrowserCapacityApplicationService capacity;
  private final BusinessRecoveryValidator defaultValidator;
  private final IdempotencyService idempotency;
  private final AuditApplicationService audit;
  private final ObjectMapper objectMapper;

  public ApplicationBusinessRecoveryService(
      ApplicationRecoveryContractJpaRepository contracts,
      ApplicationRecoveryContractRevisionJpaRepository revisions,
      ApplicationRecoveryContractApprovalJpaRepository approvals,
      SessionApplicationBindingJpaRepository bindings,
      SessionApplicationRebindJpaRepository rebinds,
      BusinessRecoveryValidationJpaRepository validations,
      BusinessRecoveryProviderEvidenceJpaRepository providerEvidence,
      SessionRepository sessions,
      OperationRepository operations,
      BrowserStateRepository browserStates,
      BrowserCapacityApplicationService capacity,
      BusinessRecoveryValidator defaultValidator,
      IdempotencyService idempotency,
      AuditApplicationService audit,
      ObjectMapper objectMapper) {
    this.contracts = contracts;
    this.revisions = revisions;
    this.approvals = approvals;
    this.bindings = bindings;
    this.rebinds = rebinds;
    this.validations = validations;
    this.providerEvidence = providerEvidence;
    this.sessions = sessions;
    this.operations = operations;
    this.browserStates = browserStates;
    this.capacity = capacity;
    this.defaultValidator = defaultValidator;
    this.idempotency = idempotency;
    this.audit = audit;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public RecoveryContractView upsertContract(
      String tenantId, String applicationId, UpsertRecoveryContractRequest request, Instant now) {
    return upsertContract(tenantId, applicationId, request, "system:internal", "", now);
  }

  @Transactional
  public RecoveryContractView upsertContract(
      String tenantId,
      String applicationId,
      UpsertRecoveryContractRequest request,
      String actorId,
      String requestId,
      Instant now) {
    validateApplicationId(applicationId);
    var normalized = normalize(request);
    var existing = contracts.findForUpdate(tenantId, applicationId);
    if (existing.isEmpty()) {
      if (request.expectedVersion() != 0) {
        throw new RecoveryContractVersionConflictException();
      }
      var entity =
          new ApplicationRecoveryContractEntity(
              newId("arc_"),
              tenantId,
              applicationId,
              write(normalized.expectedOrigins()),
              write(normalized.readyRoutePrefixes()),
              write(normalized.loginRoutePrefixes()),
              write(normalized.requiredTargets()),
              write(normalized.loginTargets()),
              write(normalized.permissionDeniedTargets()),
              write(normalized.accountMismatchTargets()),
              write(normalized.requiredExtensionIds()),
              write(normalized.requiredProviderEvidence()),
              normalized.requireDocumentComplete(),
              normalized.minimumNetworkQuietMillis(),
              write(normalized.transientBlockerTargets()),
              normalized.allowDepthLimited(),
              normalized.recoveryAction().name(),
              normalized.recoveryExtensionId(),
              normalized.maximumAutoRecovery(),
              normalized.enabled(),
              now);
      entity.updateBrowserTransactionRoutes(
          write(normalized.paymentSecurityRoutePrefixes()),
          write(normalized.criticalTransactionRoutePrefixes()));
      var saved = contracts.saveAndFlush(entity);
      appendContractAudit(saved, actorId, requestId, "RECOVERY_CONTRACT_CREATED");
      return toView(saved, Optional.empty());
    }

    var entity = existing.orElseThrow();
    if (entity.getVersion() != request.expectedVersion()) {
      if (sameConfiguration(entity, normalized)) {
        return toView(entity, currentApproval(entity));
      }
      throw new RecoveryContractVersionConflictException();
    }
    entity.update(
        write(normalized.expectedOrigins()),
        write(normalized.readyRoutePrefixes()),
        write(normalized.loginRoutePrefixes()),
        write(normalized.requiredTargets()),
        write(normalized.loginTargets()),
        write(normalized.permissionDeniedTargets()),
        write(normalized.accountMismatchTargets()),
        write(normalized.requiredExtensionIds()),
        write(normalized.requiredProviderEvidence()),
        normalized.requireDocumentComplete(),
        normalized.minimumNetworkQuietMillis(),
        write(normalized.transientBlockerTargets()),
        normalized.allowDepthLimited(),
        normalized.recoveryAction().name(),
        normalized.recoveryExtensionId(),
        normalized.maximumAutoRecovery(),
        normalized.enabled(),
        now);
    entity.updateBrowserTransactionRoutes(
        write(normalized.paymentSecurityRoutePrefixes()),
        write(normalized.criticalTransactionRoutePrefixes()));
    var saved = contracts.saveAndFlush(entity);
    appendContractAudit(saved, actorId, requestId, "RECOVERY_CONTRACT_VERSION_PUBLISHED");
    return toView(saved, currentApproval(saved));
  }

  @Transactional(readOnly = true)
  public RecoveryContractView getContract(String tenantId, String applicationId) {
    var contract =
        contracts
            .findByTenantIdAndApplicationId(tenantId, applicationId)
            .orElseThrow(RecoveryContractNotFoundException::new);
    return toView(contract, currentApproval(contract));
  }

  @Transactional(readOnly = true)
  public RecoveryContractListResponse listContracts(String tenantId) {
    var latestApprovals =
        new HashMap<ContractVersionKey, ApplicationRecoveryContractApprovalEntity>();
    approvals.findAllByTenantIdOrderByRequestedAtDesc(tenantId).stream()
        .forEach(
            approval ->
                latestApprovals.putIfAbsent(
                    new ContractVersionKey(approval.getContractId(), approval.getContractVersion()),
                    approval));
    var items =
        contracts.findAllByTenantIdOrderByApplicationIdAsc(tenantId).stream()
            .map(
                contract ->
                    toView(
                        contract,
                        Optional.ofNullable(
                            latestApprovals.get(
                                new ContractVersionKey(
                                    contract.getContractId(), contract.getVersion())))))
            .toList();
    return new RecoveryContractListResponse(items, items.size());
  }

  @Transactional(readOnly = true)
  public RecoveryContractRevisionListResponse listRevisions(String tenantId, String applicationId) {
    validateApplicationId(applicationId);
    var contract =
        contracts
            .findByTenantIdAndApplicationId(tenantId, applicationId)
            .orElseThrow(RecoveryContractNotFoundException::new);
    var latestApprovals =
        new HashMap<ContractVersionKey, ApplicationRecoveryContractApprovalEntity>();
    approvals
        .findAllByTenantIdAndContractIdOrderByRequestedAtDesc(tenantId, contract.getContractId())
        .stream()
        .forEach(
            approval ->
                latestApprovals.putIfAbsent(
                    new ContractVersionKey(approval.getContractId(), approval.getContractVersion()),
                    approval));
    var items =
        revisions
            .findAllByContractIdAndTenantIdAndApplicationIdOrderByContractVersionDesc(
                contract.getContractId(), tenantId, applicationId)
            .stream()
            .map(
                revision ->
                    toView(
                        revision,
                        Optional.ofNullable(
                            latestApprovals.get(
                                new ContractVersionKey(
                                    revision.getContractId(), revision.getContractVersion())))))
            .toList();
    return new RecoveryContractRevisionListResponse(items, items.size(), contract.getVersion());
  }

  @Transactional(readOnly = true)
  public RecoveryContractDiffView diff(
      String tenantId, String applicationId, long fromVersion, long toVersion) {
    validateApplicationId(applicationId);
    var contract =
        contracts
            .findByTenantIdAndApplicationId(tenantId, applicationId)
            .orElseThrow(RecoveryContractNotFoundException::new);
    var from = requireRevision(contract.getContractId(), fromVersion, tenantId, applicationId);
    var to = requireRevision(contract.getContractId(), toVersion, tenantId, applicationId);
    var changes = contractChanges(from, to);
    return new RecoveryContractDiffView(
        contract.getContractId(), applicationId, fromVersion, toVersion, changes, changes.size());
  }

  @Transactional
  public RecoveryContractView restoreRevision(
      String tenantId,
      String applicationId,
      RestoreRecoveryContractRevisionRequest request,
      String actorId,
      String idempotencyKey,
      String requestId,
      Instant now) {
    validateApplicationId(applicationId);
    var discovered =
        contracts
            .findByTenantIdAndApplicationId(tenantId, applicationId)
            .orElseThrow(RecoveryContractNotFoundException::new);
    var candidateRevisionId =
        discovered.getContractId()
            + ":v"
            + (request.expectedCurrentVersion() + 1)
            + ":"
            + newId("restore_");
    var revisionId =
        idempotency.claimRecoveryContractRestore(
            tenantId, applicationId, idempotencyKey, request, candidateRevisionId);
    if (!candidateRevisionId.equals(revisionId)) {
      return restoredRevisionView(tenantId, applicationId, revisionId);
    }

    var contract =
        contracts
            .findForUpdate(tenantId, applicationId)
            .orElseThrow(RecoveryContractNotFoundException::new);
    if (!contract.getContractId().equals(discovered.getContractId())
        || contract.getVersion() != request.expectedCurrentVersion()) {
      throw new RecoveryContractVersionConflictException();
    }
    if (request.sourceContractVersion() >= contract.getVersion()) {
      throw new RecoveryContractRejectedException("RESTORE_SOURCE_MUST_BE_HISTORICAL");
    }
    var source =
        requireRevision(
            contract.getContractId(), request.sourceContractVersion(), tenantId, applicationId);
    if (!isApproved(tenantId, contract.getContractId(), source.getContractVersion())) {
      throw new RecoveryContractApprovalRequiredException();
    }
    contract.update(
        source.getExpectedOrigins(),
        source.getReadyRoutePrefixes(),
        source.getLoginRoutePrefixes(),
        source.getRequiredTargets(),
        source.getLoginTargets(),
        source.getPermissionDeniedTargets(),
        source.getAccountMismatchTargets(),
        source.getRequiredExtensionIds(),
        source.getRequiredProviderEvidence(),
        source.isRequireDocumentComplete(),
        source.getMinimumNetworkQuietMillis(),
        source.getTransientBlockerTargets(),
        source.isAllowDepthLimited(),
        source.getRecoveryAction(),
        source.getRecoveryExtensionId(),
        source.getMaximumAutoRecovery(),
        source.isEnabled(),
        now.truncatedTo(ChronoUnit.MICROS));
    contract.updateBrowserTransactionRoutes(
        source.getPaymentSecurityRoutePrefixes(), source.getCriticalTransactionRoutePrefixes());
    var restored = contracts.saveAndFlush(contract);
    if (restored.getVersion() != request.expectedCurrentVersion() + 1) {
      throw new RecoveryContractVersionConflictException();
    }
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            null,
            "RECOVERY_CONTRACT",
            "USER",
            actorId,
            "APPLICATION_RECOVERY_CONTRACT",
            restored.getContractId(),
            "RECOVERY_CONTRACT_REVISION_RESTORED",
            "SUCCESS",
            Map.of(
                "applicationId",
                applicationId,
                "sourceContractVersion",
                source.getContractVersion(),
                "previousHeadVersion",
                request.expectedCurrentVersion(),
                "newContractVersion",
                restored.getVersion(),
                "reason",
                AgentDataMinimizer.redact(request.reason().strip())),
            requestId));
    return toView(restored, Optional.empty());
  }

  @Transactional
  public RecoveryContractApprovalView requestApproval(
      String tenantId,
      String applicationId,
      RequestRecoveryContractApprovalRequest request,
      String actorId,
      String requestId,
      Instant now) {
    validateApplicationId(applicationId);
    var contract =
        contracts
            .findForUpdate(tenantId, applicationId)
            .orElseThrow(RecoveryContractNotFoundException::new);
    if (contract.getVersion() != request.expectedVersion()) {
      throw new RecoveryContractVersionConflictException();
    }
    if (!contract.isEnabled()) {
      throw new RecoveryContractApprovalRejectedException("DISABLED_CONTRACT_CANNOT_BE_APPROVED");
    }
    var approved =
        approvals.findByTenantIdAndContractIdAndContractVersionAndState(
            tenantId, contract.getContractId(), contract.getVersion(), "APPROVED");
    if (approved.isPresent()) {
      return toApprovalView(approved.orElseThrow());
    }
    var pending =
        approvals.findByTenantIdAndContractIdAndContractVersionAndState(
            tenantId, contract.getContractId(), contract.getVersion(), "REQUESTED");
    if (pending.isPresent()) {
      return toApprovalView(pending.orElseThrow());
    }
    var approval =
        new ApplicationRecoveryContractApprovalEntity(
            newId("ara_"),
            tenantId,
            contract.getContractId(),
            applicationId,
            contract.getVersion(),
            AgentDataMinimizer.redact(request.reason().strip()),
            actorId,
            now.truncatedTo(ChronoUnit.MICROS));
    approval = approvals.saveAndFlush(approval);
    appendApprovalAudit(
        approval, actorId, requestId, "RECOVERY_CONTRACT_APPROVAL_REQUESTED", "PENDING");
    return toApprovalView(approval);
  }

  @Transactional
  public RecoveryContractApprovalView approve(
      String tenantId,
      String applicationId,
      String approvalId,
      String actorId,
      String requestId,
      Instant now) {
    var approval = requireApprovalForUpdate(tenantId, applicationId, approvalId);
    if ("APPROVED".equals(approval.getState())) {
      return toApprovalView(approval);
    }
    requireRequested(approval);
    if (approval.getRequestedBy().equals(actorId)) {
      audit.appendIndependent(
          approvalAuditRecord(
              approval,
              actorId,
              requestId,
              "RECOVERY_CONTRACT_APPROVAL_DENIED",
              "SEPARATION_OF_DUTIES"));
      throw new RecoveryContractApprovalRejectedException("REQUESTER_CANNOT_APPROVE");
    }
    var contract =
        contracts
            .findForUpdate(tenantId, applicationId)
            .filter(item -> item.getContractId().equals(approval.getContractId()))
            .orElseThrow(RecoveryContractNotFoundException::new);
    if (!contract.isEnabled() || contract.getVersion() != approval.getContractVersion()) {
      audit.appendIndependent(
          approvalAuditRecord(
              approval,
              actorId,
              requestId,
              "RECOVERY_CONTRACT_APPROVAL_DENIED",
              "CONTRACT_VERSION_CHANGED"));
      throw new RecoveryContractApprovalRejectedException("CONTRACT_VERSION_CHANGED");
    }
    var decidedAt = now.truncatedTo(ChronoUnit.MICROS);
    approval.approve(
        actorId, approvalEvidenceHash(contract, approval, actorId, decidedAt), decidedAt);
    approvals.save(approval);
    appendApprovalAudit(approval, actorId, requestId, "RECOVERY_CONTRACT_APPROVED", "APPROVED");
    return toApprovalView(approval);
  }

  @Transactional
  public RecoveryContractApprovalView reject(
      String tenantId,
      String applicationId,
      String approvalId,
      String actorId,
      String requestId,
      Instant now) {
    var approval = requireApprovalForUpdate(tenantId, applicationId, approvalId);
    if ("REJECTED".equals(approval.getState())) {
      return toApprovalView(approval);
    }
    requireRequested(approval);
    approval.reject(actorId, now.truncatedTo(ChronoUnit.MICROS));
    approvals.save(approval);
    appendApprovalAudit(approval, actorId, requestId, "RECOVERY_CONTRACT_REJECTED", "REJECTED");
    return toApprovalView(approval);
  }

  /** Binds a Session to an enabled, approved contract version in the creation transaction. */
  @Transactional
  public void bind(String sessionId, String tenantId, String applicationId, Instant now) {
    if (applicationId == null || applicationId.isBlank()) {
      return;
    }
    validateApplicationId(applicationId);
    var contract =
        contracts
            .findByTenantIdAndApplicationId(tenantId, applicationId)
            .filter(ApplicationRecoveryContractEntity::isEnabled)
            .orElseThrow(RecoveryContractNotFoundException::new);
    requireApproved(contract);
    bindings.save(
        new SessionApplicationBindingEntity(
            sessionId,
            tenantId,
            applicationId,
            contract.getContractId(),
            contract.getVersion(),
            now));
  }

  /** Validates a future Session binding without creating any binding or recovery state. */
  @Transactional(readOnly = true)
  public void validateBinding(String tenantId, String applicationId) {
    if (applicationId == null || applicationId.isBlank()) {
      return;
    }
    validateApplicationId(applicationId);
    var contract =
        contracts
            .findByTenantIdAndApplicationId(tenantId, applicationId)
            .filter(ApplicationRecoveryContractEntity::isEnabled)
            .orElseThrow(RecoveryContractNotFoundException::new);
    requireApproved(contract);
  }

  @Transactional(readOnly = true)
  public SessionApplicationBindingView binding(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    var binding =
        bindings
            .findBySessionIdAndTenantId(sessionId, tenantId)
            .orElseThrow(SessionApplicationBindingNotFoundException::new);
    var current =
        contracts
            .findById(binding.getContractId())
            .filter(item -> item.getTenantId().equals(tenantId))
            .filter(item -> item.getApplicationId().equals(binding.getApplicationId()))
            .orElseThrow(RecoveryContractNotFoundException::new);
    var approval = currentApproval(current);
    var approvalState =
        approval
            .map(item -> RecoveryContractApprovalState.valueOf(item.getState()))
            .orElse(RecoveryContractApprovalState.DRAFT);
    var upgradeAvailable =
        current.isEnabled()
            && current.getVersion() > binding.getContractVersion()
            && approvalState == RecoveryContractApprovalState.APPROVED;
    return new SessionApplicationBindingView(
        sessionId,
        binding.getApplicationId(),
        binding.getContractId(),
        binding.getContractVersion(),
        current.getVersion(),
        approvalState,
        current.isEnabled(),
        upgradeAvailable,
        binding.getBoundAt());
  }

  @Transactional
  public SessionApplicationRebindView rebind(
      String sessionId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      RebindSessionApplicationRequest request) {
    var candidateOperationId = newId("op_");
    var operationId =
        idempotency.claimApplicationBindingRebind(
            tenantId, sessionId, idempotencyKey, request, candidateOperationId);
    if (!candidateOperationId.equals(operationId)) {
      return rebinds
          .findByOperationIdAndTenantId(operationId, tenantId)
          .map(ApplicationBusinessRecoveryService::toRebindView)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Application binding idempotency claim has no Operation"));
    }

    var session = sessions.requireForUpdate(sessionId);
    if (!session.tenantId().equals(tenantId)) {
      throw new SessionApplicationBindingNotFoundException();
    }
    operations.ensureNoActiveOperation(sessionId);
    var binding =
        bindings
            .findForUpdate(sessionId, tenantId)
            .orElseThrow(SessionApplicationBindingNotFoundException::new);
    if (binding.getContractVersion() != request.expectedCurrentVersion()) {
      throw new RecoveryContractVersionConflictException();
    }
    if (binding.getContractVersion() == request.targetContractVersion()) {
      throw new RecoveryContractVersionConflictException();
    }
    var current =
        contracts
            .findForUpdate(tenantId, binding.getApplicationId())
            .filter(ApplicationRecoveryContractEntity::isEnabled)
            .orElseThrow(RecoveryContractNotFoundException::new);
    if (!current.getContractId().equals(binding.getContractId())
        || current.getVersion() != request.targetContractVersion()) {
      throw new RecoveryContractVersionConflictException();
    }
    requireApproved(current);
    revisions
        .findByContractIdAndContractVersionAndTenantIdAndApplicationId(
            current.getContractId(), current.getVersion(), tenantId, binding.getApplicationId())
        .filter(ApplicationRecoveryContractRevisionEntity::isEnabled)
        .orElseThrow(RecoveryContractApprovalRequiredException::new);

    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var operationEpoch = operations.nextOperationEpoch(sessionId);
    operations.insert(
        OperationFactory.committedApplicationBinding(
            session, actorId, operationEpoch, operationId));
    var previousVersion = binding.getContractVersion();
    binding.rebind(current.getContractId(), current.getVersion(), now);
    bindings.save(binding);
    var rebind =
        rebinds.save(
            new SessionApplicationRebindEntity(
                operationId,
                tenantId,
                sessionId,
                binding.getApplicationId(),
                current.getContractId(),
                previousVersion,
                current.getVersion(),
                actorId,
                requestId,
                now));
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "RECOVERY_CONTRACT_BINDING",
            "USER",
            actorId,
            "SESSION",
            sessionId,
            "SESSION_RECOVERY_CONTRACT_REBOUND",
            "SUCCESS",
            Map.of(
                "applicationId",
                binding.getApplicationId(),
                "operationId",
                operationId,
                "previousContractVersion",
                previousVersion,
                "targetContractVersion",
                current.getVersion()),
            requestId));
    return toRebindView(rebind);
  }

  @Transactional
  public BusinessRecoveryValidationView validateFromApi(
      String sessionId, String tenantId, String actorId, String idempotencyKey, String requestId) {
    return validate(sessionId, tenantId, actorId, idempotencyKey, requestId, "API", Instant.now());
  }

  @Transactional
  public BusinessRecoveryValidationView validateForMigration(
      String sessionId, String tenantId, String migrationId, int recoveryAttempt) {
    var evidenceRevision = providerEvidence.countByTenantIdAndSessionId(tenantId, sessionId);
    return validate(
        sessionId,
        tenantId,
        "system:migration",
        "business-recovery:"
            + migrationId
            + ":"
            + recoveryAttempt
            + ":provider-"
            + evidenceRevision,
        migrationId,
        "MIGRATION",
        Instant.now());
  }

  @Transactional(readOnly = true)
  public Optional<AutoRecoveryPolicy> autoRecoveryPolicy(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    var binding = bindings.findBySessionIdAndTenantId(sessionId, tenantId);
    if (binding.isEmpty()) return Optional.empty();
    var currentBinding = binding.orElseThrow();
    var current =
        contracts
            .findById(currentBinding.getContractId())
            .filter(item -> item.getTenantId().equals(tenantId))
            .filter(item -> item.getApplicationId().equals(currentBinding.getApplicationId()))
            .filter(ApplicationRecoveryContractEntity::isEnabled);
    if (current.isEmpty()
        || !isApproved(
            tenantId, currentBinding.getContractId(), currentBinding.getContractVersion())) {
      return Optional.empty();
    }
    return revisions
        .findByContractIdAndContractVersionAndTenantIdAndApplicationId(
            currentBinding.getContractId(),
            currentBinding.getContractVersion(),
            tenantId,
            currentBinding.getApplicationId())
        .filter(ApplicationRecoveryContractRevisionEntity::isEnabled)
        .map(
            contract ->
                new AutoRecoveryPolicy(
                    contract.getContractId(),
                    contract.getContractVersion(),
                    RecoveryAction.valueOf(contract.getRecoveryAction()),
                    contract.getRecoveryExtensionId(),
                    contract.getMaximumAutoRecovery(),
                    readStrings(contract.getExpectedOrigins()),
                    readStrings(contract.getReadyRoutePrefixes()),
                    readStrings(contract.getRequiredExtensionIds())));
  }

  @Transactional(readOnly = true)
  public BusinessRecoveryValidationView latest(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    return validations
        .findFirstBySessionIdAndTenantIdOrderByEvaluatedAtDesc(sessionId, tenantId)
        .map(this::toValidationView)
        .orElseThrow(BusinessRecoveryValidationNotFoundException::new);
  }

  @Transactional
  public ProviderEvidenceView submitProviderEvidence(
      String sessionId,
      String tenantId,
      String adapterActorId,
      String idempotencyKey,
      String requestId,
      SubmitProviderEvidenceRequest request,
      Instant now) {
    var session = requireTenant(sessionId, tenantId);
    var snapshot =
        browserStates
            .find(sessionId)
            .filter(value -> value.tenantId().equals(tenantId))
            .filter(value -> value.contextEpoch() == session.contextEpoch())
            .orElseThrow(BusinessRecoveryStateUnavailableException::new);
    if (request.contextEpoch() != session.contextEpoch()
        || request.stateVersion() != snapshot.state().stateVersion()) {
      throw new ProviderEvidenceRejectedException("STALE_SESSION_STATE");
    }
    var binding =
        bindings
            .findBySessionIdAndTenantId(sessionId, tenantId)
            .orElseThrow(SessionApplicationBindingNotFoundException::new);
    var contract = requireApprovedBindingContract(binding, tenantId);
    var requirement =
        readProviderRequirements(contract.getRequiredProviderEvidence()).stream()
            .filter(item -> item.type() == request.type())
            .filter(item -> item.key().equals(request.key()))
            .filter(item -> item.providerId().equals(request.providerId()))
            .findFirst()
            .orElseThrow(
                () -> new ProviderEvidenceRejectedException("REQUIREMENT_NOT_IN_BOUND_CONTRACT"));

    var evaluatedAt = now.truncatedTo(ChronoUnit.MICROS);
    var observedAt = request.observedAt().truncatedTo(ChronoUnit.MICROS);
    if (observedAt.isAfter(evaluatedAt.plusSeconds(30))
        || observedAt.isBefore(evaluatedAt.minusSeconds(requirement.maxAgeSeconds()))) {
      throw new ProviderEvidenceRejectedException("EVIDENCE_OBSERVATION_OUTSIDE_ALLOWED_WINDOW");
    }
    var observedValueHash = request.observedValueHash().toLowerCase(Locale.ROOT);
    if (request.outcome() == ProviderEvidenceOutcome.MATCH
        && !requirement.expectedValueHash().equals(observedValueHash)) {
      throw new ProviderEvidenceRejectedException("MATCH_OUTCOME_HASH_MISMATCH");
    }

    var candidateEvidenceId = newId("bre_");
    var evidenceId =
        idempotency.claimBusinessRecoveryProviderEvidence(
            tenantId, sessionId, adapterActorId, idempotencyKey, request, candidateEvidenceId);
    if (!candidateEvidenceId.equals(evidenceId)) {
      return providerEvidence
          .findById(evidenceId)
          .filter(item -> item.getTenantId().equals(tenantId))
          .filter(item -> item.getSessionId().equals(sessionId))
          .map(ApplicationBusinessRecoveryService::toProviderEvidenceView)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Provider Evidence idempotency claim has no durable evidence"));
    }

    var referenceHash = PromptSecurityService.sha256(request.providerReference().strip());
    var entity =
        providerEvidence.saveAndFlush(
            new BusinessRecoveryProviderEvidenceEntity(
                evidenceId,
                tenantId,
                sessionId,
                binding.getApplicationId(),
                contract.getContractId(),
                contract.getContractVersion(),
                session.contextEpoch(),
                snapshot.state().stateVersion(),
                request.type().name(),
                request.key(),
                request.providerId(),
                requirement.expectedValueHash(),
                observedValueHash,
                request.outcome().name(),
                referenceHash,
                adapterActorId,
                requestId,
                observedAt,
                observedAt.plusSeconds(requirement.maxAgeSeconds()),
                evaluatedAt));
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "BUSINESS_RECOVERY_PROVIDER_EVIDENCE",
            "APPLICATION_ADAPTER",
            adapterActorId,
            "SESSION",
            sessionId,
            "BUSINESS_RECOVERY_PROVIDER_EVIDENCE_RECORDED",
            request.outcome().name(),
            Map.of(
                "evidenceId",
                evidenceId,
                "applicationId",
                binding.getApplicationId(),
                "contractVersion",
                contract.getContractVersion(),
                "contextEpoch",
                session.contextEpoch(),
                "stateVersion",
                snapshot.state().stateVersion(),
                "evidenceType",
                request.type().name(),
                "evidenceKey",
                request.key(),
                "providerId",
                request.providerId(),
                "providerReferenceHash",
                referenceHash),
            requestId));
    return toProviderEvidenceView(entity);
  }

  @Transactional(readOnly = true)
  public ProviderEvidenceListResponse listProviderEvidence(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    var items =
        providerEvidence
            .findAllByTenantIdAndSessionIdOrderByCreatedAtDesc(
                tenantId, sessionId, PageRequest.of(0, 100))
            .stream()
            .map(ApplicationBusinessRecoveryService::toProviderEvidenceView)
            .toList();
    return new ProviderEvidenceListResponse(
        items, providerEvidence.countByTenantIdAndSessionId(tenantId, sessionId));
  }

  private BusinessRecoveryValidationView validate(
      String sessionId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      String source,
      Instant now) {
    var session = requireTenant(sessionId, tenantId);
    var candidateId = newId("brv_");
    var validationId =
        idempotency.claimBusinessRecoveryValidation(
            tenantId, sessionId, idempotencyKey, source, candidateId);
    if (!validationId.equals(candidateId)) {
      return validations
          .findById(validationId)
          .filter(item -> item.getTenantId().equals(tenantId))
          .map(this::toValidationView)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Business Recovery idempotency claim has no validation"));
    }

    var snapshot =
        browserStates
            .find(sessionId)
            .filter(value -> value.tenantId().equals(tenantId))
            .filter(value -> value.contextEpoch() == session.contextEpoch())
            .orElseThrow(BusinessRecoveryStateUnavailableException::new);
    var binding = bindings.findBySessionIdAndTenantId(sessionId, tenantId);
    Evaluation evaluation;
    ApplicationRecoveryContractRevisionEntity contract = null;
    if (binding.isEmpty()) {
      evaluation = evaluateDefault(snapshot.state());
    } else {
      var currentBinding = binding.orElseThrow();
      contract = requireApprovedBindingContract(currentBinding, tenantId);
      evaluation = evaluateContract(contract, snapshot.state(), sessionId, tenantId, now);
    }
    var entity =
        validations.save(
            new BusinessRecoveryValidationEntity(
                validationId,
                tenantId,
                sessionId,
                binding.map(SessionApplicationBindingEntity::getApplicationId).orElse(null),
                contract == null ? null : contract.getContractId(),
                contract == null ? null : contract.getContractVersion(),
                session.contextEpoch(),
                snapshot.state().stateVersion(),
                evaluation.verdict().name(),
                evaluation.ready(),
                write(evaluation.evidence()),
                source,
                actorId,
                requestId,
                now));
    return toValidationView(entity);
  }

  private Evaluation evaluateDefault(NodeEvent.StateUpdated state) {
    var verdict = defaultValidator.validate(state);
    if (verdict.ready()) {
      return new Evaluation(Verdict.READY, true, List.of(verdict.code()));
    }
    return new Evaluation(
        "LOGIN_REQUIRED".equals(verdict.code())
            ? Verdict.LOGIN_REQUIRED
            : Verdict.MANUAL_RECOVERY_REQUIRED,
        false,
        List.of(verdict.code()));
  }

  private Evaluation evaluateContract(
      ApplicationRecoveryContractRevisionEntity contract,
      NodeEvent.StateUpdated state,
      String sessionId,
      String tenantId,
      Instant now) {
    if (state.url() == null || state.url().isBlank()) {
      return rejected(Verdict.MANUAL_RECOVERY_REQUIRED, "RECOVERED_URL_MISSING");
    }
    if (!"COMPLETE".equals(state.stateQuality())
        && !("DEPTH_LIMITED".equals(state.stateQuality()) && contract.isAllowDepthLimited())) {
      return rejected(
          Verdict.MANUAL_RECOVERY_REQUIRED, "STATE_QUALITY_NOT_ACCEPTED:" + state.stateQuality());
    }

    final URI uri;
    try {
      uri = URI.create(state.url());
    } catch (IllegalArgumentException exception) {
      return rejected(Verdict.APPLICATION_UNAVAILABLE, "RECOVERED_URL_INVALID");
    }
    var origin = origin(uri);
    if (origin == null) {
      return rejected(Verdict.APPLICATION_UNAVAILABLE, "RECOVERED_ORIGIN_INVALID");
    }
    var expectedOrigins = readStrings(contract.getExpectedOrigins());
    if (!expectedOrigins.isEmpty() && !expectedOrigins.contains(origin)) {
      return rejected(Verdict.APPLICATION_UNAVAILABLE, "EXPECTED_ORIGIN_MISMATCH");
    }

    var path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
    if (matchesPrefix(path, readStrings(contract.getLoginRoutePrefixes()))
        || matchesAnyTarget(state.targets(), readTargets(contract.getLoginTargets()))) {
      return rejected(Verdict.LOGIN_REQUIRED, "LOGIN_INDICATOR_MATCHED");
    }
    if (matchesAnyTarget(state.targets(), readTargets(contract.getPermissionDeniedTargets()))) {
      return rejected(Verdict.PERMISSION_CHANGED, "PERMISSION_INDICATOR_MATCHED");
    }
    if (matchesAnyTarget(state.targets(), readTargets(contract.getAccountMismatchTargets()))) {
      return rejected(Verdict.ACCOUNT_MISMATCH, "ACCOUNT_INDICATOR_MATCHED");
    }
    if (contract.isRequireDocumentComplete() && !"complete".equals(state.documentReadyState())) {
      return rejected(
          Verdict.STATE_CHANGED,
          "DOCUMENT_NOT_COMPLETE:"
              + (state.documentReadyState() == null || state.documentReadyState().isBlank()
                  ? "UNKNOWN"
                  : state.documentReadyState().toUpperCase(Locale.ROOT)));
    }
    if (contract.getMinimumNetworkQuietMillis() > 0) {
      if (!state.networkEvidenceFresh()) {
        return rejected(Verdict.MANUAL_RECOVERY_REQUIRED, "NETWORK_EVIDENCE_UNAVAILABLE");
      }
      if (state.networkQuietMillis() < contract.getMinimumNetworkQuietMillis()) {
        return rejected(
            Verdict.STATE_CHANGED,
            "NETWORK_NOT_QUIET:"
                + state.networkQuietMillis()
                + "/"
                + contract.getMinimumNetworkQuietMillis());
      }
    }
    var transientBlocker =
        readTargets(contract.getTransientBlockerTargets()).stream()
            .filter(indicator -> matchesTarget(state.targets(), indicator))
            .findFirst();
    if (transientBlocker.isPresent()) {
      var indicator = transientBlocker.orElseThrow();
      return rejected(
          Verdict.STATE_CHANGED,
          "TRANSIENT_BLOCKER_MATCHED:" + indicator.role() + ":" + indicator.name());
    }
    var readyRoutes = readStrings(contract.getReadyRoutePrefixes());
    if (!readyRoutes.isEmpty() && !matchesPrefix(path, readyRoutes)) {
      return rejected(Verdict.STATE_CHANGED, "EXPECTED_ROUTE_MISMATCH");
    }
    var missingTargets =
        readTargets(contract.getRequiredTargets()).stream()
            .filter(indicator -> !matchesTarget(state.targets(), indicator))
            .map(indicator -> indicator.role() + ":" + indicator.name())
            .toList();
    if (!missingTargets.isEmpty()) {
      return rejected(
          Verdict.STATE_CHANGED, "REQUIRED_TARGETS_MISSING:" + String.join(",", missingTargets));
    }
    var requiredExtensions = readStrings(contract.getRequiredExtensionIds());
    if (!requiredExtensions.isEmpty()) {
      final Set<String> activeExtensions;
      try {
        activeExtensions = Set.copyOf(capacity.getPlacement(sessionId, tenantId).extensionIds());
      } catch (RuntimeException exception) {
        return rejected(Verdict.MANUAL_RECOVERY_REQUIRED, "PLACEMENT_EVIDENCE_UNAVAILABLE");
      }
      var missing =
          requiredExtensions.stream().filter(item -> !activeExtensions.contains(item)).toList();
      if (!missing.isEmpty()) {
        return rejected(
            Verdict.MANUAL_RECOVERY_REQUIRED,
            "REQUIRED_EXTENSIONS_MISSING:" + String.join(",", missing));
      }
    }
    var providerAssessment =
        evaluateProviderEvidence(contract, sessionId, tenantId, state.stateVersion(), now);
    if (!providerAssessment.ready()) {
      return providerAssessment;
    }
    var readyEvidence = new ArrayList<>(providerAssessment.evidence());
    if ("DEPTH_LIMITED".equals(state.stateQuality())) {
      readyEvidence.add("READY_DEPTH_LIMITED_STATE");
      return new Evaluation(Verdict.READY_WITH_WARNING, true, List.copyOf(readyEvidence));
    }
    readyEvidence.add("APPLICATION_CONTRACT_SATISFIED");
    return new Evaluation(Verdict.READY, true, List.copyOf(readyEvidence));
  }

  private Evaluation evaluateProviderEvidence(
      ApplicationRecoveryContractRevisionEntity contract,
      String sessionId,
      String tenantId,
      long stateVersion,
      Instant now) {
    var requirements = readProviderRequirements(contract.getRequiredProviderEvidence());
    if (requirements.isEmpty()) {
      return new Evaluation(Verdict.READY, true, List.of());
    }
    var session = requireTenant(sessionId, tenantId);
    var matched = new ArrayList<String>();
    for (var requirement : requirements) {
      var evidence =
          providerEvidence
              .findFirstByTenantIdAndSessionIdAndContractIdAndContractVersionAndContextEpochAndStateVersionAndEvidenceTypeAndEvidenceKeyAndProviderIdOrderByObservedAtDesc(
                  tenantId,
                  sessionId,
                  contract.getContractId(),
                  contract.getContractVersion(),
                  session.contextEpoch(),
                  stateVersion,
                  requirement.type().name(),
                  requirement.key(),
                  requirement.providerId())
              .orElse(null);
      var requirementCode =
          requirement.type() + ":" + requirement.key() + ":" + requirement.providerId();
      if (evidence == null) {
        return rejected(
            Verdict.MANUAL_RECOVERY_REQUIRED, "PROVIDER_EVIDENCE_MISSING:" + requirementCode);
      }
      if (evidence.getExpiresAt().isBefore(now)
          || evidence.getObservedAt().isBefore(now.minusSeconds(requirement.maxAgeSeconds()))) {
        return rejected(
            Verdict.MANUAL_RECOVERY_REQUIRED, "PROVIDER_EVIDENCE_EXPIRED:" + requirementCode);
      }
      if ("UNKNOWN".equals(evidence.getOutcome())) {
        return rejected(
            Verdict.MANUAL_RECOVERY_REQUIRED, "PROVIDER_EVIDENCE_UNKNOWN:" + requirementCode);
      }
      if ("MISMATCH".equals(evidence.getOutcome())
          || !requirement.expectedValueHash().equals(evidence.getObservedValueHash())) {
        return rejected(
            providerMismatchVerdict(requirement.type()),
            "PROVIDER_EVIDENCE_MISMATCH:" + requirementCode);
      }
      matched.add("PROVIDER_EVIDENCE_MATCHED:" + requirementCode + ":" + evidence.getEvidenceId());
    }
    return new Evaluation(Verdict.READY, true, List.copyOf(matched));
  }

  private static Verdict providerMismatchVerdict(ProviderEvidenceType type) {
    return switch (type) {
      case ACCOUNT, TENANT_WORKSPACE -> Verdict.ACCOUNT_MISMATCH;
      case PERMISSION -> Verdict.PERMISSION_CHANGED;
      case BUSINESS_ENTITY -> Verdict.STATE_CHANGED;
    };
  }

  private NormalizedContract normalize(UpsertRecoveryContractRequest request) {
    var expectedOrigins =
        stringList(request.expectedOrigins()).stream()
            .map(ApplicationBusinessRecoveryService::normalizeOrigin)
            .distinct()
            .sorted()
            .toList();
    if (expectedOrigins.isEmpty()) {
      throw new RecoveryContractRejectedException("EXPECTED_ORIGIN_REQUIRED");
    }
    var recoveryAction =
        request.recoveryAction() == null ? RecoveryAction.NONE : request.recoveryAction();
    if (request.recoveryAction() != null
        && (request.maximumAutoRecovery() == 0) != (recoveryAction == RecoveryAction.NONE)) {
      throw new RecoveryContractRejectedException("AUTO_RECOVERY_ACTION_BUDGET_MISMATCH");
    }
    var requiredExtensionIds = identifierList(request.requiredExtensionIds());
    var recoveryExtensionId =
        request.recoveryExtensionId() == null || request.recoveryExtensionId().isBlank()
            ? null
            : request.recoveryExtensionId().strip();
    if (recoveryAction == RecoveryAction.RESTART_EXTENSION) {
      if (recoveryExtensionId == null
          || !recoveryExtensionId.matches("^[a-p]{32}$")
          || !requiredExtensionIds.contains(recoveryExtensionId)) {
        throw new RecoveryContractRejectedException(
            "RECOVERY_EXTENSION_MUST_BE_REQUIRED_CHROMIUM_EXTENSION");
      }
    } else if (recoveryExtensionId != null) {
      throw new RecoveryContractRejectedException(
          "RECOVERY_EXTENSION_REQUIRES_RESTART_EXTENSION_ACTION");
    }
    return new NormalizedContract(
        expectedOrigins,
        routeList(request.readyRoutePrefixes()),
        routeList(request.loginRoutePrefixes()),
        targetList(request.requiredTargets()),
        targetList(request.loginTargets()),
        targetList(request.permissionDeniedTargets()),
        targetList(request.accountMismatchTargets()),
        requiredExtensionIds,
        providerRequirementList(request.requiredProviderEvidence()),
        request.requireDocumentComplete(),
        request.minimumNetworkQuietMillis(),
        targetList(request.transientBlockerTargets()),
        lowerRouteList(request.paymentSecurityRoutePrefixes()),
        lowerRouteList(request.criticalTransactionRoutePrefixes()),
        request.allowDepthLimited(),
        recoveryAction,
        recoveryExtensionId,
        request.maximumAutoRecovery(),
        request.enabled());
  }

  private boolean sameConfiguration(
      ApplicationRecoveryContractEntity entity, NormalizedContract value) {
    return readStrings(entity.getExpectedOrigins()).equals(value.expectedOrigins())
        && readStrings(entity.getReadyRoutePrefixes()).equals(value.readyRoutePrefixes())
        && readStrings(entity.getLoginRoutePrefixes()).equals(value.loginRoutePrefixes())
        && readTargets(entity.getRequiredTargets()).equals(value.requiredTargets())
        && readTargets(entity.getLoginTargets()).equals(value.loginTargets())
        && readTargets(entity.getPermissionDeniedTargets()).equals(value.permissionDeniedTargets())
        && readTargets(entity.getAccountMismatchTargets()).equals(value.accountMismatchTargets())
        && readStrings(entity.getRequiredExtensionIds()).equals(value.requiredExtensionIds())
        && readProviderRequirements(entity.getRequiredProviderEvidence())
            .equals(value.requiredProviderEvidence())
        && entity.isRequireDocumentComplete() == value.requireDocumentComplete()
        && entity.getMinimumNetworkQuietMillis() == value.minimumNetworkQuietMillis()
        && readTargets(entity.getTransientBlockerTargets()).equals(value.transientBlockerTargets())
        && readStrings(entity.getPaymentSecurityRoutePrefixes())
            .equals(value.paymentSecurityRoutePrefixes())
        && readStrings(entity.getCriticalTransactionRoutePrefixes())
            .equals(value.criticalTransactionRoutePrefixes())
        && entity.isAllowDepthLimited() == value.allowDepthLimited()
        && entity.getRecoveryAction().equals(value.recoveryAction().name())
        && java.util.Objects.equals(entity.getRecoveryExtensionId(), value.recoveryExtensionId())
        && entity.getMaximumAutoRecovery() == value.maximumAutoRecovery()
        && entity.isEnabled() == value.enabled();
  }

  private RecoveryContractView toView(
      ApplicationRecoveryContractEntity entity,
      Optional<ApplicationRecoveryContractApprovalEntity> approval) {
    var currentApproval = approval.orElse(null);
    return new RecoveryContractView(
        entity.getContractId(),
        entity.getApplicationId(),
        entity.getVersion(),
        readStrings(entity.getExpectedOrigins()),
        readStrings(entity.getReadyRoutePrefixes()),
        readStrings(entity.getLoginRoutePrefixes()),
        readTargets(entity.getRequiredTargets()),
        readTargets(entity.getLoginTargets()),
        readTargets(entity.getPermissionDeniedTargets()),
        readTargets(entity.getAccountMismatchTargets()),
        readStrings(entity.getRequiredExtensionIds()),
        readProviderRequirements(entity.getRequiredProviderEvidence()),
        entity.isRequireDocumentComplete(),
        entity.getMinimumNetworkQuietMillis(),
        readTargets(entity.getTransientBlockerTargets()),
        readStrings(entity.getPaymentSecurityRoutePrefixes()),
        readStrings(entity.getCriticalTransactionRoutePrefixes()),
        entity.isAllowDepthLimited(),
        RecoveryAction.valueOf(entity.getRecoveryAction()),
        entity.getRecoveryExtensionId(),
        entity.getMaximumAutoRecovery(),
        entity.isEnabled(),
        currentApproval == null
            ? RecoveryContractApprovalState.DRAFT
            : RecoveryContractApprovalState.valueOf(currentApproval.getState()),
        currentApproval == null ? null : currentApproval.getApprovalId(),
        currentApproval == null ? null : currentApproval.getRequestedBy(),
        currentApproval == null ? null : currentApproval.getApprovedBy(),
        currentApproval == null ? null : currentApproval.getRequestedAt(),
        currentApproval == null ? null : currentApproval.getDecidedAt(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private RecoveryContractView toView(
      ApplicationRecoveryContractRevisionEntity entity,
      Optional<ApplicationRecoveryContractApprovalEntity> approval) {
    var currentApproval = approval.orElse(null);
    return new RecoveryContractView(
        entity.getContractId(),
        entity.getApplicationId(),
        entity.getContractVersion(),
        readStrings(entity.getExpectedOrigins()),
        readStrings(entity.getReadyRoutePrefixes()),
        readStrings(entity.getLoginRoutePrefixes()),
        readTargets(entity.getRequiredTargets()),
        readTargets(entity.getLoginTargets()),
        readTargets(entity.getPermissionDeniedTargets()),
        readTargets(entity.getAccountMismatchTargets()),
        readStrings(entity.getRequiredExtensionIds()),
        readProviderRequirements(entity.getRequiredProviderEvidence()),
        entity.isRequireDocumentComplete(),
        entity.getMinimumNetworkQuietMillis(),
        readTargets(entity.getTransientBlockerTargets()),
        readStrings(entity.getPaymentSecurityRoutePrefixes()),
        readStrings(entity.getCriticalTransactionRoutePrefixes()),
        entity.isAllowDepthLimited(),
        RecoveryAction.valueOf(entity.getRecoveryAction()),
        entity.getRecoveryExtensionId(),
        entity.getMaximumAutoRecovery(),
        entity.isEnabled(),
        currentApproval == null
            ? RecoveryContractApprovalState.DRAFT
            : RecoveryContractApprovalState.valueOf(currentApproval.getState()),
        currentApproval == null ? null : currentApproval.getApprovalId(),
        currentApproval == null ? null : currentApproval.getRequestedBy(),
        currentApproval == null ? null : currentApproval.getApprovedBy(),
        currentApproval == null ? null : currentApproval.getRequestedAt(),
        currentApproval == null ? null : currentApproval.getDecidedAt(),
        entity.getContractCreatedAt(),
        entity.getPublishedAt());
  }

  private RecoveryContractView restoredRevisionView(
      String tenantId, String applicationId, String revisionId) {
    var separator = revisionId.lastIndexOf(":v");
    var suffix = revisionId.indexOf(':', separator + 2);
    if (separator <= 0 || suffix <= separator + 2) {
      throw new IllegalStateException("Recovery Contract restore claim has invalid revision ID");
    }
    final long version;
    try {
      version = Long.parseLong(revisionId.substring(separator + 2, suffix));
    } catch (NumberFormatException exception) {
      throw new IllegalStateException(
          "Recovery Contract restore claim has invalid revision version", exception);
    }
    var revision =
        requireRevision(revisionId.substring(0, separator), version, tenantId, applicationId);
    return toView(revision, latestApproval(tenantId, revision.getContractId(), version));
  }

  private ApplicationRecoveryContractRevisionEntity requireRevision(
      String contractId, long version, String tenantId, String applicationId) {
    return revisions
        .findByContractIdAndContractVersionAndTenantIdAndApplicationId(
            contractId, version, tenantId, applicationId)
        .orElseThrow(RecoveryContractNotFoundException::new);
  }

  private Optional<ApplicationRecoveryContractApprovalEntity> latestApproval(
      String tenantId, String contractId, long version) {
    return approvals.findFirstByTenantIdAndContractIdAndContractVersionOrderByRequestedAtDesc(
        tenantId, contractId, version);
  }

  private List<RecoveryContractFieldChange> contractChanges(
      ApplicationRecoveryContractRevisionEntity from,
      ApplicationRecoveryContractRevisionEntity to) {
    var changes = new ArrayList<RecoveryContractFieldChange>();
    addChange(changes, "expectedOrigins", from.getExpectedOrigins(), to.getExpectedOrigins());
    addChange(
        changes, "readyRoutePrefixes", from.getReadyRoutePrefixes(), to.getReadyRoutePrefixes());
    addChange(
        changes, "loginRoutePrefixes", from.getLoginRoutePrefixes(), to.getLoginRoutePrefixes());
    addChange(changes, "requiredTargets", from.getRequiredTargets(), to.getRequiredTargets());
    addChange(changes, "loginTargets", from.getLoginTargets(), to.getLoginTargets());
    addChange(
        changes,
        "permissionDeniedTargets",
        from.getPermissionDeniedTargets(),
        to.getPermissionDeniedTargets());
    addChange(
        changes,
        "accountMismatchTargets",
        from.getAccountMismatchTargets(),
        to.getAccountMismatchTargets());
    addChange(
        changes,
        "requiredExtensionIds",
        from.getRequiredExtensionIds(),
        to.getRequiredExtensionIds());
    addChange(
        changes,
        "requiredProviderEvidence",
        from.getRequiredProviderEvidence(),
        to.getRequiredProviderEvidence());
    addChange(
        changes,
        "requireDocumentComplete",
        Boolean.toString(from.isRequireDocumentComplete()),
        Boolean.toString(to.isRequireDocumentComplete()));
    addChange(
        changes,
        "minimumNetworkQuietMillis",
        Integer.toString(from.getMinimumNetworkQuietMillis()),
        Integer.toString(to.getMinimumNetworkQuietMillis()));
    addChange(
        changes,
        "transientBlockerTargets",
        from.getTransientBlockerTargets(),
        to.getTransientBlockerTargets());
    addChange(
        changes,
        "paymentSecurityRoutePrefixes",
        from.getPaymentSecurityRoutePrefixes(),
        to.getPaymentSecurityRoutePrefixes());
    addChange(
        changes,
        "criticalTransactionRoutePrefixes",
        from.getCriticalTransactionRoutePrefixes(),
        to.getCriticalTransactionRoutePrefixes());
    addChange(
        changes,
        "allowDepthLimited",
        Boolean.toString(from.isAllowDepthLimited()),
        Boolean.toString(to.isAllowDepthLimited()));
    addChange(changes, "recoveryAction", from.getRecoveryAction(), to.getRecoveryAction());
    addChange(
        changes,
        "recoveryExtensionId",
        Objects.toString(from.getRecoveryExtensionId(), "null"),
        Objects.toString(to.getRecoveryExtensionId(), "null"));
    addChange(
        changes,
        "maximumAutoRecovery",
        Integer.toString(from.getMaximumAutoRecovery()),
        Integer.toString(to.getMaximumAutoRecovery()));
    addChange(
        changes, "enabled", Boolean.toString(from.isEnabled()), Boolean.toString(to.isEnabled()));
    return List.copyOf(changes);
  }

  private static void addChange(
      List<RecoveryContractFieldChange> changes, String field, String before, String after) {
    if (!Objects.equals(before, after)) {
      changes.add(new RecoveryContractFieldChange(field, "MODIFIED", before, after));
    }
  }

  private Optional<ApplicationRecoveryContractApprovalEntity> currentApproval(
      ApplicationRecoveryContractEntity contract) {
    return approvals.findFirstByTenantIdAndContractIdAndContractVersionOrderByRequestedAtDesc(
        contract.getTenantId(), contract.getContractId(), contract.getVersion());
  }

  private boolean isApproved(ApplicationRecoveryContractEntity contract) {
    return approvals.existsByTenantIdAndContractIdAndContractVersionAndState(
        contract.getTenantId(), contract.getContractId(), contract.getVersion(), "APPROVED");
  }

  private void requireApproved(ApplicationRecoveryContractEntity contract) {
    if (!isApproved(contract)) {
      throw new RecoveryContractApprovalRequiredException();
    }
  }

  private ApplicationRecoveryContractRevisionEntity requireApprovedBindingContract(
      SessionApplicationBindingEntity binding, String tenantId) {
    var current =
        contracts
            .findById(binding.getContractId())
            .filter(item -> item.getTenantId().equals(tenantId))
            .filter(item -> item.getApplicationId().equals(binding.getApplicationId()))
            .filter(ApplicationRecoveryContractEntity::isEnabled)
            .orElseThrow(RecoveryContractApprovalRequiredException::new);
    if (!isApproved(tenantId, binding.getContractId(), binding.getContractVersion())) {
      throw new RecoveryContractApprovalRequiredException();
    }
    return revisions
        .findByContractIdAndContractVersionAndTenantIdAndApplicationId(
            current.getContractId(),
            binding.getContractVersion(),
            tenantId,
            binding.getApplicationId())
        .filter(ApplicationRecoveryContractRevisionEntity::isEnabled)
        .orElseThrow(RecoveryContractApprovalRequiredException::new);
  }

  private boolean isApproved(String tenantId, String contractId, long contractVersion) {
    return approvals.existsByTenantIdAndContractIdAndContractVersionAndState(
        tenantId, contractId, contractVersion, "APPROVED");
  }

  private ApplicationRecoveryContractApprovalEntity requireApprovalForUpdate(
      String tenantId, String applicationId, String approvalId) {
    validateApplicationId(applicationId);
    return approvals
        .findForUpdate(approvalId, tenantId, applicationId)
        .orElseThrow(RecoveryContractApprovalNotFoundException::new);
  }

  private static void requireRequested(ApplicationRecoveryContractApprovalEntity approval) {
    if (!"REQUESTED".equals(approval.getState())) {
      throw new RecoveryContractApprovalRejectedException(
          "INVALID_APPROVAL_STATE_" + approval.getState());
    }
  }

  private void appendContractAudit(
      ApplicationRecoveryContractEntity contract, String actorId, String requestId, String action) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            contract.getTenantId(),
            null,
            "RECOVERY_CONTRACT",
            "USER",
            actorId,
            "APPLICATION_RECOVERY_CONTRACT",
            contract.getContractId(),
            action,
            "SUCCESS",
            Map.of(
                "applicationId", contract.getApplicationId(),
                "contractVersion", contract.getVersion(),
                "enabled", contract.isEnabled()),
            requestId));
  }

  private void appendApprovalAudit(
      ApplicationRecoveryContractApprovalEntity approval,
      String actorId,
      String requestId,
      String action,
      String result) {
    audit.append(approvalAuditRecord(approval, actorId, requestId, action, result));
  }

  private static AuditApplicationService.AuditRecord approvalAuditRecord(
      ApplicationRecoveryContractApprovalEntity approval,
      String actorId,
      String requestId,
      String action,
      String result) {
    return new AuditApplicationService.AuditRecord(
        approval.getTenantId(),
        null,
        "RECOVERY_CONTRACT_APPROVAL",
        "USER",
        actorId,
        "APPLICATION_RECOVERY_CONTRACT",
        approval.getContractId(),
        action,
        result,
        Map.of(
            "approvalId", approval.getApprovalId(),
            "applicationId", approval.getApplicationId(),
            "contractVersion", approval.getContractVersion(),
            "requestedBy", approval.getRequestedBy()),
        requestId);
  }

  private static String approvalEvidenceHash(
      ApplicationRecoveryContractEntity contract,
      ApplicationRecoveryContractApprovalEntity approval,
      String actorId,
      Instant decidedAt) {
    return PromptSecurityService.sha256(
        String.join(
            "|",
            approval.getApprovalId(),
            contract.getTenantId(),
            contract.getContractId(),
            contract.getApplicationId(),
            Long.toString(contract.getVersion()),
            contract.getExpectedOrigins(),
            contract.getReadyRoutePrefixes(),
            contract.getLoginRoutePrefixes(),
            contract.getRequiredTargets(),
            contract.getLoginTargets(),
            contract.getPermissionDeniedTargets(),
            contract.getAccountMismatchTargets(),
            contract.getRequiredExtensionIds(),
            contract.getRequiredProviderEvidence(),
            Boolean.toString(contract.isRequireDocumentComplete()),
            Integer.toString(contract.getMinimumNetworkQuietMillis()),
            contract.getTransientBlockerTargets(),
            Boolean.toString(contract.isAllowDepthLimited()),
            contract.getRecoveryAction(),
            Objects.toString(contract.getRecoveryExtensionId(), ""),
            Integer.toString(contract.getMaximumAutoRecovery()),
            Boolean.toString(contract.isEnabled()),
            approval.getReason(),
            approval.getRequestedBy(),
            actorId,
            approval.getRequestedAt().toString(),
            decidedAt.toString()));
  }

  private static RecoveryContractApprovalView toApprovalView(
      ApplicationRecoveryContractApprovalEntity approval) {
    return new RecoveryContractApprovalView(
        approval.getApprovalId(),
        approval.getContractId(),
        approval.getApplicationId(),
        approval.getContractVersion(),
        approval.getReason(),
        RecoveryContractApprovalState.valueOf(approval.getState()),
        approval.getRequestedBy(),
        approval.getApprovedBy(),
        approval.getRejectedBy(),
        approval.getRequestedAt(),
        approval.getDecidedAt(),
        approval.getEvidenceHash());
  }

  private static SessionApplicationRebindView toRebindView(SessionApplicationRebindEntity rebind) {
    return new SessionApplicationRebindView(
        rebind.getOperationId(),
        rebind.getSessionId(),
        rebind.getApplicationId(),
        rebind.getContractId(),
        rebind.getPreviousContractVersion(),
        rebind.getTargetContractVersion(),
        "COMMITTED",
        rebind.getRequestId(),
        rebind.getCreatedAt(),
        rebind.getCompletedAt());
  }

  private BusinessRecoveryValidationView toValidationView(BusinessRecoveryValidationEntity entity) {
    return new BusinessRecoveryValidationView(
        entity.getValidationId(),
        entity.getSessionId(),
        entity.getApplicationId(),
        entity.getContractVersion(),
        entity.getContextEpoch(),
        entity.getStateVersion(),
        Verdict.valueOf(entity.getVerdict()),
        entity.isReady(),
        readStrings(entity.getEvidence()),
        entity.getSource(),
        entity.getRequestId(),
        entity.getEvaluatedAt());
  }

  private static ProviderEvidenceView toProviderEvidenceView(
      BusinessRecoveryProviderEvidenceEntity entity) {
    return new ProviderEvidenceView(
        entity.getEvidenceId(),
        entity.getSessionId(),
        entity.getApplicationId(),
        entity.getContractVersion(),
        entity.getContextEpoch(),
        entity.getStateVersion(),
        ProviderEvidenceType.valueOf(entity.getEvidenceType()),
        entity.getEvidenceKey(),
        entity.getProviderId(),
        ProviderEvidenceOutcome.valueOf(entity.getOutcome()),
        entity.getExpectedValueHash().equals(entity.getObservedValueHash()),
        entity.getProviderReferenceHash(),
        entity.getAdapterActorId(),
        entity.getRequestId(),
        entity.getObservedAt(),
        entity.getExpiresAt(),
        entity.getCreatedAt());
  }

  private io.browsercloud.domain.session.SessionContext requireTenant(
      String sessionId, String tenantId) {
    var session = sessions.require(sessionId);
    if (!session.tenantId().equals(tenantId)) {
      throw new BusinessRecoveryValidationNotFoundException();
    }
    return session;
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Business Recovery contract is not serializable", exception);
    }
  }

  private List<String> readStrings(String value) {
    if (value == null || value.isBlank()) return List.of();
    try {
      return objectMapper.readValue(value, STRING_LIST);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored Business Recovery list is invalid", exception);
    }
  }

  private List<TargetIndicator> readTargets(String value) {
    try {
      return objectMapper.readValue(value, TARGET_LIST);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored Business Recovery target list is invalid", exception);
    }
  }

  private List<ProviderEvidenceRequirement> readProviderRequirements(String value) {
    try {
      return objectMapper.readValue(value, PROVIDER_REQUIREMENT_LIST);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Stored Business Recovery Provider requirement list is invalid", exception);
    }
  }

  private static String normalizeOrigin(String value) {
    try {
      var uri = new URI(value.strip());
      var scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
      if (!Set.of("http", "https").contains(scheme)
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new RecoveryContractRejectedException("EXPECTED_ORIGIN_INVALID");
      }
      return new URI(
              scheme,
              null,
              uri.getHost().toLowerCase(Locale.ROOT),
              normalizePort(uri),
              null,
              null,
              null)
          .toString();
    } catch (URISyntaxException | IllegalArgumentException exception) {
      throw new RecoveryContractRejectedException("EXPECTED_ORIGIN_INVALID");
    }
  }

  private static int normalizePort(URI uri) {
    if (uri.getPort() == 80 && "http".equalsIgnoreCase(uri.getScheme())) return -1;
    if (uri.getPort() == 443 && "https".equalsIgnoreCase(uri.getScheme())) return -1;
    return uri.getPort();
  }

  private static String origin(URI uri) {
    if (uri.getScheme() == null || uri.getHost() == null) return null;
    try {
      return new URI(
              uri.getScheme().toLowerCase(Locale.ROOT),
              null,
              uri.getHost().toLowerCase(Locale.ROOT),
              normalizePort(uri),
              null,
              null,
              null)
          .toString();
    } catch (URISyntaxException exception) {
      return null;
    }
  }

  private static List<String> routeList(List<String> values) {
    return stringList(values).stream()
        .map(
            value -> {
              var route = value.strip();
              if (!route.startsWith("/")
                  || route.contains("..")
                  || route.indexOf('?') >= 0
                  || route.indexOf('#') >= 0) {
                throw new RecoveryContractRejectedException("ROUTE_PREFIX_INVALID");
              }
              return route;
            })
        .distinct()
        .sorted()
        .toList();
  }

  private static List<String> lowerRouteList(List<String> values) {
    return routeList(values).stream()
        .map(value -> value.toLowerCase(Locale.ROOT))
        .distinct()
        .sorted()
        .toList();
  }

  private static List<String> identifierList(List<String> values) {
    return stringList(values).stream()
        .peek(
            value -> {
              if (!value.matches("^[a-zA-Z0-9_.-]{1,128}$")) {
                throw new RecoveryContractRejectedException("EXTENSION_ID_INVALID");
              }
            })
        .distinct()
        .sorted()
        .toList();
  }

  private static List<TargetIndicator> targetList(List<TargetIndicator> values) {
    if (values == null) return List.of();
    return values.stream()
        .map(
            value ->
                new TargetIndicator(
                    value.role().strip().toLowerCase(Locale.ROOT), value.name().strip()))
        .distinct()
        .sorted(Comparator.comparing(TargetIndicator::role).thenComparing(TargetIndicator::name))
        .toList();
  }

  private static List<ProviderEvidenceRequirement> providerRequirementList(
      List<ProviderEvidenceRequirement> values) {
    if (values == null) return List.of();
    var normalized =
        values.stream()
            .map(
                value ->
                    new ProviderEvidenceRequirement(
                        value.type(),
                        value.key().strip(),
                        value.providerId().strip(),
                        value.expectedValueHash().strip().toLowerCase(Locale.ROOT),
                        value.maxAgeSeconds()))
            .sorted(
                Comparator.comparing((ProviderEvidenceRequirement value) -> value.type().name())
                    .thenComparing(ProviderEvidenceRequirement::key)
                    .thenComparing(ProviderEvidenceRequirement::providerId))
            .toList();
    var keys = new HashSet<String>();
    for (var value : normalized) {
      var key = value.type() + ":" + value.key() + ":" + value.providerId();
      if (!keys.add(key)) {
        throw new RecoveryContractRejectedException("PROVIDER_EVIDENCE_REQUIREMENT_DUPLICATE");
      }
    }
    return normalized;
  }

  private static List<String> stringList(List<String> values) {
    if (values == null) return List.of();
    return values.stream().map(String::strip).filter(value -> !value.isBlank()).toList();
  }

  private static boolean matchesPrefix(String path, List<String> prefixes) {
    return prefixes.stream().anyMatch(path::startsWith);
  }

  private static boolean matchesAnyTarget(
      List<NodeEvent.InteractiveTarget> targets, List<TargetIndicator> indicators) {
    return indicators.stream().anyMatch(indicator -> matchesTarget(targets, indicator));
  }

  private static boolean matchesTarget(
      List<NodeEvent.InteractiveTarget> targets, TargetIndicator indicator) {
    if (targets == null || targets.isEmpty()) {
      return false;
    }
    return targets.stream()
        .filter(Objects::nonNull)
        .filter(NodeEvent.InteractiveTarget::visible)
        .anyMatch(
            target ->
                indicator.role().equalsIgnoreCase(target.role())
                    && indicator.name().equalsIgnoreCase(target.name()));
  }

  private static Evaluation rejected(Verdict verdict, String evidence) {
    return new Evaluation(verdict, false, List.of(evidence));
  }

  private static void validateApplicationId(String applicationId) {
    if (applicationId == null || !applicationId.matches("^[a-zA-Z0-9_.-]{1,128}$")) {
      throw new RecoveryContractRejectedException("APPLICATION_ID_INVALID");
    }
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private record Evaluation(Verdict verdict, boolean ready, List<String> evidence) {}

  private record ContractVersionKey(String contractId, long contractVersion) {}

  private record NormalizedContract(
      List<String> expectedOrigins,
      List<String> readyRoutePrefixes,
      List<String> loginRoutePrefixes,
      List<TargetIndicator> requiredTargets,
      List<TargetIndicator> loginTargets,
      List<TargetIndicator> permissionDeniedTargets,
      List<TargetIndicator> accountMismatchTargets,
      List<String> requiredExtensionIds,
      List<ProviderEvidenceRequirement> requiredProviderEvidence,
      boolean requireDocumentComplete,
      int minimumNetworkQuietMillis,
      List<TargetIndicator> transientBlockerTargets,
      List<String> paymentSecurityRoutePrefixes,
      List<String> criticalTransactionRoutePrefixes,
      boolean allowDepthLimited,
      RecoveryAction recoveryAction,
      String recoveryExtensionId,
      int maximumAutoRecovery,
      boolean enabled) {}

  public record AutoRecoveryPolicy(
      String contractId,
      long contractVersion,
      RecoveryAction action,
      String recoveryExtensionId,
      int maximumAttempts,
      List<String> expectedOrigins,
      List<String> readyRoutePrefixes,
      List<String> requiredExtensionIds) {
    public AutoRecoveryPolicy {
      expectedOrigins = List.copyOf(expectedOrigins);
      readyRoutePrefixes = List.copyOf(readyRoutePrefixes);
      requiredExtensionIds = List.copyOf(requiredExtensionIds);
    }
  }

  public static final class RecoveryContractNotFoundException extends RuntimeException {}

  public static final class RecoveryContractVersionConflictException extends RuntimeException {}

  public static final class RecoveryContractApprovalRequiredException extends RuntimeException {}

  public static final class RecoveryContractApprovalNotFoundException extends RuntimeException {}

  public static final class RecoveryContractApprovalRejectedException extends RuntimeException {
    public RecoveryContractApprovalRejectedException(String message) {
      super(message);
    }
  }

  public static final class RecoveryContractRejectedException extends RuntimeException {
    public RecoveryContractRejectedException(String message) {
      super(message);
    }
  }

  public static final class ProviderEvidenceRejectedException extends RuntimeException {
    public ProviderEvidenceRejectedException(String message) {
      super(message);
    }
  }

  public static final class BusinessRecoveryStateUnavailableException extends RuntimeException {}

  public static final class BusinessRecoveryValidationNotFoundException extends RuntimeException {}

  public static final class SessionApplicationBindingNotFoundException extends RuntimeException {}
}
