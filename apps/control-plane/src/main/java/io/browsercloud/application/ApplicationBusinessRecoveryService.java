package io.browsercloud.application;

import static io.browsercloud.api.BusinessRecoveryModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.persistence.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.*;
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

  private final ApplicationRecoveryContractJpaRepository contracts;
  private final SessionApplicationBindingJpaRepository bindings;
  private final BusinessRecoveryValidationJpaRepository validations;
  private final SessionRepository sessions;
  private final BrowserStateRepository browserStates;
  private final BrowserCapacityApplicationService capacity;
  private final BusinessRecoveryValidator defaultValidator;
  private final IdempotencyService idempotency;
  private final ObjectMapper objectMapper;

  public ApplicationBusinessRecoveryService(
      ApplicationRecoveryContractJpaRepository contracts,
      SessionApplicationBindingJpaRepository bindings,
      BusinessRecoveryValidationJpaRepository validations,
      SessionRepository sessions,
      BrowserStateRepository browserStates,
      BrowserCapacityApplicationService capacity,
      BusinessRecoveryValidator defaultValidator,
      IdempotencyService idempotency,
      ObjectMapper objectMapper) {
    this.contracts = contracts;
    this.bindings = bindings;
    this.validations = validations;
    this.sessions = sessions;
    this.browserStates = browserStates;
    this.capacity = capacity;
    this.defaultValidator = defaultValidator;
    this.idempotency = idempotency;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public RecoveryContractView upsertContract(
      String tenantId, String applicationId, UpsertRecoveryContractRequest request, Instant now) {
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
              normalized.allowDepthLimited(),
              normalized.recoveryAction().name(),
              normalized.maximumAutoRecovery(),
              normalized.enabled(),
              now);
      return toView(contracts.saveAndFlush(entity));
    }

    var entity = existing.orElseThrow();
    if (entity.getVersion() != request.expectedVersion()) {
      if (sameConfiguration(entity, normalized)) {
        return toView(entity);
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
        normalized.allowDepthLimited(),
        normalized.recoveryAction().name(),
        normalized.maximumAutoRecovery(),
        normalized.enabled(),
        now);
    return toView(contracts.saveAndFlush(entity));
  }

  @Transactional(readOnly = true)
  public RecoveryContractView getContract(String tenantId, String applicationId) {
    return contracts
        .findByTenantIdAndApplicationId(tenantId, applicationId)
        .map(this::toView)
        .orElseThrow(RecoveryContractNotFoundException::new);
  }

  @Transactional(readOnly = true)
  public RecoveryContractListResponse listContracts(String tenantId) {
    var items =
        contracts.findAllByTenantIdOrderByApplicationIdAsc(tenantId).stream()
            .map(this::toView)
            .toList();
    return new RecoveryContractListResponse(items, items.size());
  }

  /** Binds a Session to an enabled contract in the same transaction as Session creation. */
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
    bindings.save(
        new SessionApplicationBindingEntity(
            sessionId, tenantId, applicationId, contract.getContractId(), now));
  }

  @Transactional
  public BusinessRecoveryValidationView validateFromApi(
      String sessionId, String tenantId, String actorId, String idempotencyKey, String requestId) {
    return validate(sessionId, tenantId, actorId, idempotencyKey, requestId, "API", Instant.now());
  }

  @Transactional
  public BusinessRecoveryValidationView validateForMigration(
      String sessionId, String tenantId, String migrationId, int recoveryAttempt) {
    return validate(
        sessionId,
        tenantId,
        "system:migration",
        "business-recovery:" + migrationId + ":" + recoveryAttempt,
        migrationId,
        "MIGRATION",
        Instant.now());
  }

  @Transactional(readOnly = true)
  public Optional<AutoRecoveryPolicy> autoRecoveryPolicy(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    return bindings
        .findBySessionIdAndTenantId(sessionId, tenantId)
        .flatMap(
            binding ->
                contracts
                    .findById(binding.getContractId())
                    .filter(item -> item.getTenantId().equals(tenantId))
                    .filter(ApplicationRecoveryContractEntity::isEnabled))
        .map(
            contract ->
                new AutoRecoveryPolicy(
                    contract.getContractId(),
                    contract.getVersion(),
                    RecoveryAction.valueOf(contract.getRecoveryAction()),
                    contract.getMaximumAutoRecovery(),
                    readStrings(contract.getExpectedOrigins()),
                    readStrings(contract.getReadyRoutePrefixes())));
  }

  @Transactional(readOnly = true)
  public BusinessRecoveryValidationView latest(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    return validations
        .findFirstBySessionIdAndTenantIdOrderByEvaluatedAtDesc(sessionId, tenantId)
        .map(this::toValidationView)
        .orElseThrow(BusinessRecoveryValidationNotFoundException::new);
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
    ApplicationRecoveryContractEntity contract = null;
    if (binding.isEmpty()) {
      evaluation = evaluateDefault(snapshot.state());
    } else {
      var currentBinding = binding.orElseThrow();
      contract =
          contracts
              .findById(currentBinding.getContractId())
              .filter(item -> item.getTenantId().equals(tenantId))
              .filter(ApplicationRecoveryContractEntity::isEnabled)
              .orElseThrow(RecoveryContractNotFoundException::new);
      evaluation = evaluateContract(contract, snapshot.state(), sessionId, tenantId);
    }
    var entity =
        validations.save(
            new BusinessRecoveryValidationEntity(
                validationId,
                tenantId,
                sessionId,
                binding.map(SessionApplicationBindingEntity::getApplicationId).orElse(null),
                contract == null ? null : contract.getContractId(),
                contract == null ? null : contract.getVersion(),
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
      ApplicationRecoveryContractEntity contract,
      NodeEvent.StateUpdated state,
      String sessionId,
      String tenantId) {
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
    if ("DEPTH_LIMITED".equals(state.stateQuality())) {
      return new Evaluation(Verdict.READY_WITH_WARNING, true, List.of("READY_DEPTH_LIMITED_STATE"));
    }
    return new Evaluation(Verdict.READY, true, List.of("APPLICATION_CONTRACT_SATISFIED"));
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
    return new NormalizedContract(
        expectedOrigins,
        routeList(request.readyRoutePrefixes()),
        routeList(request.loginRoutePrefixes()),
        targetList(request.requiredTargets()),
        targetList(request.loginTargets()),
        targetList(request.permissionDeniedTargets()),
        targetList(request.accountMismatchTargets()),
        identifierList(request.requiredExtensionIds()),
        request.allowDepthLimited(),
        recoveryAction,
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
        && entity.isAllowDepthLimited() == value.allowDepthLimited()
        && entity.getRecoveryAction().equals(value.recoveryAction().name())
        && entity.getMaximumAutoRecovery() == value.maximumAutoRecovery()
        && entity.isEnabled() == value.enabled();
  }

  private RecoveryContractView toView(ApplicationRecoveryContractEntity entity) {
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
        entity.isAllowDepthLimited(),
        RecoveryAction.valueOf(entity.getRecoveryAction()),
        entity.getMaximumAutoRecovery(),
        entity.isEnabled(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
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

  private record NormalizedContract(
      List<String> expectedOrigins,
      List<String> readyRoutePrefixes,
      List<String> loginRoutePrefixes,
      List<TargetIndicator> requiredTargets,
      List<TargetIndicator> loginTargets,
      List<TargetIndicator> permissionDeniedTargets,
      List<TargetIndicator> accountMismatchTargets,
      List<String> requiredExtensionIds,
      boolean allowDepthLimited,
      RecoveryAction recoveryAction,
      int maximumAutoRecovery,
      boolean enabled) {}

  public record AutoRecoveryPolicy(
      String contractId,
      long contractVersion,
      RecoveryAction action,
      int maximumAttempts,
      List<String> expectedOrigins,
      List<String> readyRoutePrefixes) {
    public AutoRecoveryPolicy {
      expectedOrigins = List.copyOf(expectedOrigins);
      readyRoutePrefixes = List.copyOf(readyRoutePrefixes);
    }
  }

  public static final class RecoveryContractNotFoundException extends RuntimeException {}

  public static final class RecoveryContractVersionConflictException extends RuntimeException {}

  public static final class RecoveryContractRejectedException extends RuntimeException {
    public RecoveryContractRejectedException(String message) {
      super(message);
    }
  }

  public static final class BusinessRecoveryStateUnavailableException extends RuntimeException {}

  public static final class BusinessRecoveryValidationNotFoundException extends RuntimeException {}
}
