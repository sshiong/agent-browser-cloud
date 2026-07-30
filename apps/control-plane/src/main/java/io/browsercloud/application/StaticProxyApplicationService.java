package io.browsercloud.application;

import io.browsercloud.api.ProxyAllocationView;
import io.browsercloud.api.ProxyBindingModels.ProxyBindingListResponse;
import io.browsercloud.api.ProxyBindingModels.ProxyBindingRequest;
import io.browsercloud.api.ProxyBindingModels.ProxyBindingView;
import io.browsercloud.api.ProxyOverviewResponse;
import io.browsercloud.api.ProxyProviderView;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.persistence.ProxyAllocationEntity;
import io.browsercloud.persistence.ProxyAllocationJpaRepository;
import io.browsercloud.persistence.ProxyBindingProfileEntity;
import io.browsercloud.persistence.ProxyBindingProfileJpaRepository;
import io.browsercloud.persistence.SessionProxyBindingAssignmentEntity;
import io.browsercloud.persistence.SessionProxyBindingAssignmentJpaRepository;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 单 Provider Static Proxy MVP；未绑定或验证失败时不允许隐式回退直连。 */
@Service
public class StaticProxyApplicationService {

  private static final List<String> ACTIVE_STATES = List.of("ALLOCATED", "BOUND");

  private final ProxyAllocationJpaRepository repository;
  private final ProxyBindingProfileJpaRepository bindingProfiles;
  private final SessionProxyBindingAssignmentJpaRepository bindingAssignments;
  private final SessionRepository sessionRepository;
  private final IdempotencyService idempotency;
  private final AuditApplicationService audit;
  private final String providerId;
  private final String endpoint;
  private final String expectedExitIp;
  private final boolean allowDirect;

  public StaticProxyApplicationService(
      ProxyAllocationJpaRepository repository,
      ProxyBindingProfileJpaRepository bindingProfiles,
      SessionProxyBindingAssignmentJpaRepository bindingAssignments,
      SessionRepository sessionRepository,
      IdempotencyService idempotency,
      AuditApplicationService audit,
      @Value("${proxy.static.provider-id:static-local}") String providerId,
      @Value("${proxy.static.endpoint:}") String endpoint,
      @Value("${proxy.static.expected-exit-ip:}") String expectedExitIp,
      @Value("${proxy.allow-direct:false}") boolean allowDirect,
      @Value("${app.environment:local}") String environment) {
    this.repository = repository;
    this.bindingProfiles = bindingProfiles;
    this.bindingAssignments = bindingAssignments;
    this.sessionRepository = sessionRepository;
    this.idempotency = idempotency;
    this.audit = audit;
    this.providerId = requireIdentifier(providerId, "proxy provider ID");
    this.endpoint = endpoint.trim();
    this.expectedExitIp = expectedExitIp.trim();
    this.allowDirect = allowDirect;
    if (environment.equalsIgnoreCase("production")) {
      if (allowDirect) {
        throw new IllegalStateException("proxy.allow-direct cannot be enabled in production");
      }
      if (this.endpoint.isEmpty()) {
        throw new IllegalStateException("proxy.static.endpoint is required in production");
      }
    }
    if (!this.endpoint.isEmpty()) {
      validateEndpoint(this.endpoint);
      if (this.expectedExitIp.isEmpty()) {
        throw new IllegalStateException("proxy.static.expected-exit-ip is required");
      }
    }
  }

  @Transactional
  public SessionContext ensureBinding(SessionContext session) {
    if (session.proxyBindingId() != null && !session.proxyBindingId().isBlank()) {
      return session;
    }
    if (endpoint.isEmpty()) {
      if (allowDirect) {
        return session;
      }
      throw new ProxyUnavailableException("No static proxy provider is configured");
    }
    var existing = repository.findFirstBySessionIdAndStateIn(session.sessionId(), ACTIVE_STATES);
    if (existing.isPresent()) {
      var rebound = session.withProxyBinding(existing.orElseThrow().getAllocationId());
      sessionRepository.updateWithExpectedEpoch(rebound, session.contextEpoch());
      return rebound;
    }
    var allocationId = "pxy_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    var assignment =
        bindingAssignments.findBySessionIdAndTenantId(session.sessionId(), session.tenantId());
    var selectedProvider =
        assignment.map(SessionProxyBindingAssignmentEntity::getProviderId).orElse(providerId);
    if (!providerId.equals(selectedProvider)) {
      throw new ProxyUnavailableException(
          "Selected proxy provider is not configured on this Node fleet");
    }
    repository.save(
        new ProxyAllocationEntity(
            allocationId,
            session.tenantId(),
            session.sessionId(),
            selectedProvider,
            endpoint,
            assignment.map(SessionProxyBindingAssignmentEntity::getBindingProfileId).orElse(null),
            assignment.map(SessionProxyBindingAssignmentEntity::getBindingVersion).orElse(null),
            assignment
                .map(SessionProxyBindingAssignmentEntity::getExpectedExitIp)
                .orElse(expectedExitIp),
            assignment.map(SessionProxyBindingAssignmentEntity::getCredentialRef).orElse(""),
            Instant.now()));
    var boundContext = session.withProxyBinding(allocationId);
    sessionRepository.updateWithExpectedEpoch(boundContext, session.contextEpoch());
    return boundContext;
  }

  @Transactional
  public void recordBound(String tenantId, NodeEvent.RuntimeStarted event) {
    if (event.proxyBindingId().isBlank()) {
      if (!allowDirect) {
        throw new ProxyUnavailableException("Runtime started without a required proxy binding");
      }
      return;
    }
    var allocation =
        repository
            .findById(event.proxyBindingId())
            .orElseThrow(() -> new ProxyUnavailableException("Proxy allocation does not exist"));
    if (!allocation.getTenantId().equals(tenantId)
        || !allocation.getSessionId().equals(event.sessionId())) {
      throw new ProxyUnavailableException("Proxy allocation identity does not match the Session");
    }
    var requiredExitIp =
        allocation.getExpectedExitIp() == null ? expectedExitIp : allocation.getExpectedExitIp();
    if (!requiredExitIp.equals(event.exitIp())) {
      throw new ProxyUnavailableException("Observed proxy exit does not match the allocation");
    }
    var now = Instant.now();
    allocation.bind(event.exitIp(), event.exitCountry(), event.exitAsn(), now);
    repository.save(allocation);
    if (allocation.getBindingProfileId() != null) {
      bindingProfiles
          .findByBindingProfileIdAndTenantId(
              allocation.getBindingProfileId(), allocation.getTenantId())
          .ifPresent(
              profile -> {
                profile.markHealthy(event.exitIp(), now);
                bindingProfiles.save(profile);
              });
    }
  }

  @Transactional
  public void release(String sessionId) {
    repository
        .findFirstBySessionIdAndStateIn(sessionId, ACTIVE_STATES)
        .ifPresent(
            allocation -> {
              allocation.release(Instant.now());
              repository.save(allocation);
            });
  }

  public boolean isDirectAllowed() {
    return allowDirect;
  }

  public String providerId() {
    return providerId;
  }

  public String endpoint() {
    return endpoint;
  }

  public String expectedExitIp() {
    return expectedExitIp;
  }

  @Transactional(readOnly = true)
  public ProxyOverviewResponse overview(String tenantId) {
    var allocations =
        repository.findAllByTenantIdOrderByAllocatedAtDesc(tenantId).stream()
            .map(
                allocation ->
                    new ProxyAllocationView(
                        allocation.getAllocationId(),
                        allocation.getSessionId(),
                        allocation.getProvider(),
                        allocation.getProtocol(),
                        allocation.getState(),
                        allocation.getExitIp(),
                        allocation.getCountry(),
                        allocation.getAsn(),
                        allocation.getAllocatedAt(),
                        allocation.getVerifiedAt(),
                        allocation.getReleasedAt(),
                        allocation.getUpdatedAt()))
            .toList();
    var provider =
        new ProxyProviderView(
            providerId,
            "STATIC_HTTP",
            endpoint,
            expectedExitIp,
            allowDirect,
            endpoint.isEmpty() ? "UNCONFIGURED" : "CONFIGURED");
    return new ProxyOverviewResponse(provider, allocations, allocations.size());
  }

  @Transactional(readOnly = true)
  public ProxyBindingListResponse listBindings(String tenantId) {
    var items =
        bindingProfiles.findAllByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
            .map(this::toBindingView)
            .toList();
    return new ProxyBindingListResponse(items, items.size());
  }

  @Transactional
  public ProxyBindingView createBinding(
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      ProxyBindingRequest request) {
    validateBindingConfiguration(request);
    if (request.credentialRef() == null || request.credentialRef().isBlank()) {
      throw new ProxyBindingRejectedException("CREDENTIAL_REFERENCE_REQUIRED");
    }
    var candidate = newId("pbind_");
    var bindingProfileId =
        idempotency.claimProxyBindingCreate(tenantId, idempotencyKey, request, candidate);
    if (!candidate.equals(bindingProfileId)) {
      return toBindingView(requireBinding(bindingProfileId, tenantId));
    }
    rejectDuplicateName(tenantId, request.name(), null);
    var now = Instant.now();
    var profile =
        bindingProfiles.save(
            new ProxyBindingProfileEntity(
                bindingProfileId,
                tenantId,
                request.name(),
                request.description(),
                request.providerId(),
                request.region(),
                request.expectedExitIp(),
                request.credentialRef(),
                request.enabled(),
                actorId,
                now));
    appendBindingAudit(
        tenantId,
        actorId,
        bindingProfileId,
        "PROXY_BINDING_CREATED",
        requestId,
        Map.of(
            "name", profile.getName(),
            "providerId", profile.getProviderId(),
            "enabled", profile.isEnabled()));
    return toBindingView(profile);
  }

  @Transactional
  public ProxyBindingView updateBinding(
      String tenantId,
      String actorId,
      String bindingProfileId,
      String idempotencyKey,
      String requestId,
      ProxyBindingRequest request) {
    validateBindingConfiguration(request);
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimProxyBindingMutation(
            tenantId, bindingProfileId, "UPDATE", idempotencyKey, request, candidateMutation);
    var profile = requireBinding(bindingProfileId, tenantId);
    if (!candidateMutation.equals(mutation)) {
      return toBindingView(profile);
    }
    if (request.expectedVersion() == null || request.expectedVersion() != profile.getVersion()) {
      throw new ProxyBindingRejectedException("STALE_BINDING_VERSION");
    }
    rejectDuplicateName(tenantId, request.name(), bindingProfileId);
    profile.update(
        request.name(),
        request.description(),
        request.providerId(),
        request.region(),
        request.expectedExitIp(),
        request.credentialRef() == null || request.credentialRef().isBlank()
            ? profile.getCredentialRef()
            : request.credentialRef(),
        request.enabled(),
        Instant.now());
    bindingProfiles.saveAndFlush(profile);
    appendBindingAudit(
        tenantId,
        actorId,
        bindingProfileId,
        "PROXY_BINDING_UPDATED",
        requestId,
        Map.of(
            "name", profile.getName(),
            "providerId", profile.getProviderId(),
            "enabled", profile.isEnabled()));
    return toBindingView(profile);
  }

  @Transactional
  public void deleteBinding(
      String tenantId,
      String actorId,
      String bindingProfileId,
      String idempotencyKey,
      String requestId) {
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimProxyBindingMutation(
            tenantId,
            bindingProfileId,
            "DELETE",
            idempotencyKey,
            bindingProfileId,
            candidateMutation);
    var profile = bindingProfiles.findByBindingProfileIdAndTenantId(bindingProfileId, tenantId);
    if (!candidateMutation.equals(mutation) || profile.isEmpty()) {
      return;
    }
    if (bindingAssignments.existsByTenantIdAndBindingProfileId(tenantId, bindingProfileId)
        || repository.existsByTenantIdAndBindingProfileId(tenantId, bindingProfileId)) {
      throw new ProxyBindingRejectedException("BINDING_HAS_SESSION_ASSIGNMENTS");
    }
    bindingProfiles.delete(profile.orElseThrow());
    appendBindingAudit(
        tenantId,
        actorId,
        bindingProfileId,
        "PROXY_BINDING_DELETED",
        requestId,
        Map.of("name", profile.orElseThrow().getName()));
  }

  @Transactional
  public void assignBindingProfile(
      SessionContext session, String bindingProfileId, String sessionRegion, String actorId) {
    if (bindingProfileId == null || bindingProfileId.isBlank()) {
      return;
    }
    var profile = requireBinding(bindingProfileId, session.tenantId());
    if (!profile.isEnabled()) {
      throw new ProxyBindingRejectedException("BINDING_IS_DISABLED");
    }
    if (profile.getRegion() != null && !profile.getRegion().equals(sessionRegion)) {
      throw new ProxyBindingRejectedException("BINDING_REGION_MISMATCH");
    }
    bindingAssignments.save(
        new SessionProxyBindingAssignmentEntity(
            session.sessionId(),
            session.tenantId(),
            profile.getBindingProfileId(),
            profile.getVersion(),
            profile.getProviderId(),
            profile.getRegion(),
            profile.getExpectedExitIp(),
            profile.getCredentialRef(),
            actorId,
            Instant.now()));
  }

  @Transactional(readOnly = true)
  public String assignedBindingProfileId(String sessionId, String tenantId) {
    return bindingAssignments
        .findBySessionIdAndTenantId(sessionId, tenantId)
        .map(SessionProxyBindingAssignmentEntity::getBindingProfileId)
        .orElse(null);
  }

  private void validateBindingConfiguration(ProxyBindingRequest request) {
    if (!providerId.equals(request.providerId())) {
      throw new ProxyBindingRejectedException("PROVIDER_NOT_CONFIGURED");
    }
    if (endpoint.isEmpty()) {
      throw new ProxyBindingRejectedException("PROVIDER_NOT_CONFIGURED");
    }
    if (!expectedExitIp.equals(request.expectedExitIp().strip())) {
      throw new ProxyBindingRejectedException("EXPECTED_EXIT_NOT_CONFIGURED");
    }
  }

  private ProxyBindingProfileEntity requireBinding(String bindingProfileId, String tenantId) {
    return bindingProfiles
        .findByBindingProfileIdAndTenantId(bindingProfileId, tenantId)
        .orElseThrow(() -> new ProxyBindingNotFoundException(bindingProfileId));
  }

  private void rejectDuplicateName(String tenantId, String name, String currentId) {
    bindingProfiles.findAllByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
        .filter(profile -> !profile.getBindingProfileId().equals(currentId))
        .filter(profile -> profile.getName().equalsIgnoreCase(name.strip()))
        .findFirst()
        .ifPresent(
            ignored -> {
              throw new ProxyBindingRejectedException("BINDING_NAME_ALREADY_EXISTS");
            });
  }

  private ProxyBindingView toBindingView(ProxyBindingProfileEntity profile) {
    return new ProxyBindingView(
        profile.getBindingProfileId(),
        profile.getName(),
        profile.getDescription(),
        profile.getProviderId(),
        profile.getRegion(),
        profile.getExpectedExitIp(),
        profile.getCredentialRef() != null && !profile.getCredentialRef().isBlank(),
        profile.isEnabled(),
        profile.getHealthState(),
        profile.getLastVerifiedExitIp(),
        profile.getLastHealthCheckedAt(),
        profile.getLastFailureReason(),
        profile.getVersion(),
        profile.getCreatedBy(),
        profile.getCreatedAt(),
        profile.getUpdatedAt());
  }

  private void appendBindingAudit(
      String tenantId,
      String actorId,
      String bindingProfileId,
      String action,
      String requestId,
      Map<String, Object> details) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            null,
            "PROXY_BINDING",
            "USER",
            actorId,
            "PROXY_BINDING",
            bindingProfileId,
            action,
            "COMMITTED",
            details,
            requestId));
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private static String requireIdentifier(String value, String name) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || !value
            .chars()
            .allMatch(
                character ->
                    Character.isLetterOrDigit(character) || character == '_' || character == '-')) {
      throw new IllegalStateException(name + " is invalid");
    }
    return value;
  }

  private static void validateEndpoint(String value) {
    var uri = URI.create(value);
    if (!"http".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getPort() <= 0
        || uri.getUserInfo() != null
        || (uri.getPath() != null && !uri.getPath().isBlank())
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalStateException("Static proxy endpoint must be http://host:port");
    }
  }

  public static final class ProxyUnavailableException extends RuntimeException {
    public ProxyUnavailableException(String message) {
      super(message);
    }
  }

  public static final class ProxyBindingNotFoundException extends RuntimeException {
    public ProxyBindingNotFoundException(String bindingProfileId) {
      super(bindingProfileId);
    }
  }

  public static final class ProxyBindingRejectedException extends RuntimeException {
    public ProxyBindingRejectedException(String reason) {
      super(reason);
    }
  }
}
