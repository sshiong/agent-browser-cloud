package io.browsercloud.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.ProxyAllocationView;
import io.browsercloud.api.ProxyBindingModels.ProxyBindingListResponse;
import io.browsercloud.api.ProxyBindingModels.ProxyBindingRequest;
import io.browsercloud.api.ProxyBindingModels.ProxyBindingView;
import io.browsercloud.api.ProxyOverviewResponse;
import io.browsercloud.api.ProxyProviderView;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.ProxyAllocationEntity;
import io.browsercloud.persistence.ProxyAllocationJpaRepository;
import io.browsercloud.persistence.ProxyBindingProfileEntity;
import io.browsercloud.persistence.ProxyBindingProfileJpaRepository;
import io.browsercloud.persistence.SessionProxyBindingAssignmentEntity;
import io.browsercloud.persistence.SessionProxyBindingAssignmentJpaRepository;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Provider Catalog 驱动的静态 Proxy；未绑定或验证失败时不允许隐式回退直连。 */
@Service
public class StaticProxyApplicationService {

  private static final List<String> ACTIVE_STATES = List.of("ALLOCATED", "BOUND");

  private final ProxyAllocationJpaRepository repository;
  private final ProxyBindingProfileJpaRepository bindingProfiles;
  private final SessionProxyBindingAssignmentJpaRepository bindingAssignments;
  private final SessionRepository sessionRepository;
  private final IdempotencyService idempotency;
  private final AuditApplicationService audit;
  private final Map<ProviderKey, ProviderDescriptor> providers;
  private final ProviderDescriptor defaultProvider;
  private final String providerId;
  private final String endpoint;
  private final String expectedExitIp;
  private final boolean allowDirect;

  @Autowired
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
      @Value("${proxy.static.credential-ref:}") String credentialRef,
      @Value("${proxy.provider-config-file:}") String providerConfigFile,
      @Value("${proxy.allow-direct:false}") boolean allowDirect,
      @Value("${app.environment:local}") String environment) {
    this.repository = repository;
    this.bindingProfiles = bindingProfiles;
    this.bindingAssignments = bindingAssignments;
    this.sessionRepository = sessionRepository;
    this.idempotency = idempotency;
    this.audit = audit;
    var fallbackProvider =
        new ProviderDescriptor(
            requireIdentifier(providerId, "proxy provider ID"),
            endpoint.trim(),
            expectedExitIp.trim(),
            credentialRef == null ? "" : credentialRef.trim());
    this.providers = loadProviderCatalog(providerConfigFile, fallbackProvider);
    this.defaultProvider =
        this.providers.size() == 1 ? this.providers.values().iterator().next() : null;
    this.providerId = defaultProvider == null ? "provider-catalog" : defaultProvider.providerId();
    this.endpoint = defaultProvider == null ? "" : defaultProvider.endpoint();
    this.expectedExitIp = defaultProvider == null ? "" : defaultProvider.expectedExitIp();
    this.allowDirect = allowDirect;
    if (environment.equalsIgnoreCase("production")) {
      if (allowDirect) {
        throw new IllegalStateException("proxy.allow-direct cannot be enabled in production");
      }
      if (this.providers.isEmpty()) {
        throw new IllegalStateException("at least one proxy provider is required in production");
      }
    }
  }

  /** Unit/local compatibility constructor for the single-provider environment contract. */
  public StaticProxyApplicationService(
      ProxyAllocationJpaRepository repository,
      ProxyBindingProfileJpaRepository bindingProfiles,
      SessionProxyBindingAssignmentJpaRepository bindingAssignments,
      SessionRepository sessionRepository,
      IdempotencyService idempotency,
      AuditApplicationService audit,
      String providerId,
      String endpoint,
      String expectedExitIp,
      boolean allowDirect,
      String environment) {
    this(
        repository,
        bindingProfiles,
        bindingAssignments,
        sessionRepository,
        idempotency,
        audit,
        providerId,
        endpoint,
        expectedExitIp,
        "",
        "",
        allowDirect,
        environment);
  }

  public StaticProxyApplicationService(
      ProxyAllocationJpaRepository repository,
      ProxyBindingProfileJpaRepository bindingProfiles,
      SessionProxyBindingAssignmentJpaRepository bindingAssignments,
      SessionRepository sessionRepository,
      IdempotencyService idempotency,
      AuditApplicationService audit,
      String providerId,
      String endpoint,
      String expectedExitIp,
      String credentialRef,
      boolean allowDirect,
      String environment) {
    this(
        repository,
        bindingProfiles,
        bindingAssignments,
        sessionRepository,
        idempotency,
        audit,
        providerId,
        endpoint,
        expectedExitIp,
        credentialRef,
        "",
        allowDirect,
        environment);
  }

  private static Map<ProviderKey, ProviderDescriptor> loadProviderCatalog(
      String providerConfigFile, ProviderDescriptor fallback) {
    if (providerConfigFile == null || providerConfigFile.isBlank()) {
      if (fallback.endpoint().isEmpty()) {
        return Map.of();
      }
      validateProvider(fallback);
      return Map.of(new ProviderKey(fallback.providerId(), fallback.credentialRef()), fallback);
    }
    try {
      var path = Path.of(providerConfigFile);
      if (!path.isAbsolute()) {
        throw new IllegalStateException("proxy provider config path must be absolute");
      }
      var attributes =
          Files.readAttributes(
              path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
        throw new IllegalStateException("proxy provider config must be a regular file");
      }
      if (attributes.size() > 1024 * 1024) {
        throw new IllegalStateException("proxy provider config exceeds 1 MiB");
      }
      try {
        var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!java.util.Collections.disjoint(
            permissions,
            Set.of(
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE))) {
          throw new IllegalStateException(
              "proxy provider config must not be accessible by other users");
        }
      } catch (UnsupportedOperationException ignored) {
        // Non-POSIX deployments rely on the platform ACL of the mounted Secret volume.
      }
      var document = new ObjectMapper().readValue(path.toFile(), ProviderConfigDocument.class);
      if (document.version() != 1
          || document.providers() == null
          || document.providers().isEmpty()
          || document.providers().size() > 256) {
        throw new IllegalStateException(
            "proxy provider config must be version 1 with 1..256 providers");
      }
      var configured = new HashMap<ProviderKey, ProviderDescriptor>();
      for (var entry : document.providers()) {
        var descriptor =
            new ProviderDescriptor(
                requireIdentifier(entry.providerId(), "proxy provider ID"),
                entry.endpoint() == null ? "" : entry.endpoint().strip(),
                entry.expectedExitIp() == null ? "" : entry.expectedExitIp().strip(),
                entry.credentialRef() == null ? "" : entry.credentialRef().strip());
        validateProvider(descriptor);
        var previous =
            configured.put(
                new ProviderKey(descriptor.providerId(), descriptor.credentialRef()), descriptor);
        if (previous != null) {
          throw new IllegalStateException("duplicate proxy provider and credential reference");
        }
      }
      return Map.copyOf(configured);
    } catch (IllegalStateException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalStateException("cannot load proxy provider config", error);
    }
  }

  @Transactional
  public SessionContext ensureBinding(SessionContext session) {
    if (session.proxyBindingId() != null && !session.proxyBindingId().isBlank()) {
      var committed =
          repository
              .findById(session.proxyBindingId())
              .filter(allocation -> allocation.getSessionId().equals(session.sessionId()))
              .filter(allocation -> allocation.getTenantId().equals(session.tenantId()))
              .filter(allocation -> ACTIVE_STATES.contains(allocation.getState()));
      if (committed.isPresent()) {
        return session;
      }
    }
    if (providers.isEmpty()) {
      if (allowDirect) {
        if (session.proxyBindingId() == null || session.proxyBindingId().isBlank()) {
          return session;
        }
        var directContext = session.withProxyBinding(null);
        sessionRepository.updateWithExpectedEpoch(directContext, session.contextEpoch());
        return directContext;
      }
      throw new ProxyUnavailableException("No proxy provider is configured");
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
        assignment
            .map(
                item ->
                    requireConfiguredProvider(
                        item.getProviderId(), item.getCredentialRef(), item.getExpectedExitIp()))
            .orElseGet(this::requireDefaultProvider);
    repository.save(
        new ProxyAllocationEntity(
            allocationId,
            session.tenantId(),
            session.sessionId(),
            selectedProvider.providerId(),
            selectedProvider.endpoint(),
            assignment.map(SessionProxyBindingAssignmentEntity::getBindingProfileId).orElse(null),
            assignment.map(SessionProxyBindingAssignmentEntity::getBindingVersion).orElse(null),
            assignment
                .map(SessionProxyBindingAssignmentEntity::getExpectedExitIp)
                .orElse(selectedProvider.expectedExitIp()),
            assignment
                .map(SessionProxyBindingAssignmentEntity::getCredentialRef)
                .orElse(selectedProvider.credentialRef()),
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
            providers.size() > 1 ? "STATIC_HTTP_CATALOG" : "STATIC_HTTP",
            endpoint,
            expectedExitIp,
            allowDirect,
            providers.isEmpty()
                ? "UNCONFIGURED"
                : providers.size() == 1 ? "CONFIGURED" : "CATALOG_CONFIGURED");
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
    var profile = requireBinding(bindingProfileId, tenantId);
    var effectiveCredentialRef =
        request.credentialRef() == null || request.credentialRef().isBlank()
            ? profile.getCredentialRef()
            : request.credentialRef();
    requireConfiguredProvider(
        request.providerId(), effectiveCredentialRef, request.expectedExitIp());
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimProxyBindingMutation(
            tenantId, bindingProfileId, "UPDATE", idempotencyKey, request, candidateMutation);
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
        effectiveCredentialRef,
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
    requireConfiguredProvider(
        profile.getProviderId(), profile.getCredentialRef(), profile.getExpectedExitIp());
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

  @Transactional(readOnly = true)
  public RebindTargetSnapshot validateRebindTarget(
      String sessionId, String tenantId, String bindingProfileId, String sessionRegion) {
    var profile = requireBinding(bindingProfileId, tenantId);
    if (!profile.isEnabled()) {
      throw new ProxyBindingRejectedException("BINDING_IS_DISABLED");
    }
    if (profile.getRegion() != null && !profile.getRegion().equals(sessionRegion)) {
      throw new ProxyBindingRejectedException("BINDING_REGION_MISMATCH");
    }
    requireConfiguredProvider(
        profile.getProviderId(), profile.getCredentialRef(), profile.getExpectedExitIp());
    var sourceProfileId =
        bindingAssignments
            .findBySessionIdAndTenantId(sessionId, tenantId)
            .map(SessionProxyBindingAssignmentEntity::getBindingProfileId)
            .orElse(null);
    if (bindingProfileId.equals(sourceProfileId)) {
      throw new ProxyBindingRejectedException("BINDING_ALREADY_ASSIGNED");
    }
    return new RebindTargetSnapshot(
        sourceProfileId, profile.getBindingProfileId(), profile.getVersion());
  }

  /**
   * Commits only after RuntimeStopped released the source allocation and the Session reached
   * HIBERNATED. The next Session start allocates a fresh per-Session binding from this snapshot.
   */
  @Transactional
  public SessionContext commitRebindAfterHibernate(
      String sessionId,
      String tenantId,
      String targetBindingProfileId,
      long expectedBindingVersion,
      String actorId,
      String requestId,
      String workflowId,
      String sessionRegion) {
    var session = sessionRepository.requireForUpdate(sessionId);
    if (!session.tenantId().equals(tenantId) || session.state() != SessionState.HIBERNATED) {
      throw new ProxyBindingRejectedException("REBIND_REQUIRES_HIBERNATED_SESSION");
    }
    if (session.proxyBindingId() != null && !session.proxyBindingId().isBlank()) {
      var sourceAllocation =
          repository
              .findById(session.proxyBindingId())
              .orElseThrow(() -> new ProxyBindingRejectedException("SOURCE_ALLOCATION_NOT_FOUND"));
      if (!"RELEASED".equals(sourceAllocation.getState())) {
        throw new ProxyBindingRejectedException("SOURCE_ALLOCATION_NOT_RELEASED");
      }
    }
    var profile = requireBinding(targetBindingProfileId, tenantId);
    if (!profile.isEnabled() || profile.getVersion() != expectedBindingVersion) {
      throw new ProxyBindingRejectedException("TARGET_BINDING_CHANGED");
    }
    if (profile.getRegion() != null && !profile.getRegion().equals(sessionRegion)) {
      throw new ProxyBindingRejectedException("BINDING_REGION_MISMATCH");
    }
    requireConfiguredProvider(
        profile.getProviderId(), profile.getCredentialRef(), profile.getExpectedExitIp());
    bindingAssignments.save(
        new SessionProxyBindingAssignmentEntity(
            sessionId,
            tenantId,
            profile.getBindingProfileId(),
            profile.getVersion(),
            profile.getProviderId(),
            profile.getRegion(),
            profile.getExpectedExitIp(),
            profile.getCredentialRef(),
            actorId,
            Instant.now()));
    var rebound = session.withProxyBinding(null);
    sessionRepository.updateWithExpectedEpoch(rebound, session.contextEpoch());
    appendBindingAudit(
        tenantId,
        actorId,
        targetBindingProfileId,
        "SESSION_PROXY_REBIND_COMMITTED",
        requestId,
        Map.of(
            "sessionId", sessionId,
            "workflowId", workflowId,
            "bindingVersion", profile.getVersion()));
    return rebound;
  }

  private void validateBindingConfiguration(ProxyBindingRequest request) {
    requireConfiguredProvider(
        request.providerId(), request.credentialRef(), request.expectedExitIp());
  }

  private ProviderDescriptor requireDefaultProvider() {
    if (defaultProvider == null) {
      throw new ProxyUnavailableException(
          providers.isEmpty()
              ? "No proxy provider is configured"
              : "A proxy binding profile is required when multiple providers are configured");
    }
    return defaultProvider;
  }

  private ProviderDescriptor requireConfiguredProvider(
      String requestedProviderId, String requestedCredentialRef, String requestedExitIp) {
    var credentialRef = requestedCredentialRef == null ? "" : requestedCredentialRef.strip();
    var provider = providers.get(new ProviderKey(requestedProviderId, credentialRef));
    if (provider == null) {
      throw new ProxyBindingRejectedException("PROVIDER_OR_CREDENTIAL_NOT_CONFIGURED");
    }
    if (requestedExitIp == null || !provider.expectedExitIp().equals(requestedExitIp.strip())) {
      throw new ProxyBindingRejectedException("EXPECTED_EXIT_NOT_CONFIGURED");
    }
    return provider;
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

  private static void validateProvider(ProviderDescriptor provider) {
    if (provider.endpoint().isEmpty()) {
      throw new IllegalStateException("proxy provider endpoint is required");
    }
    validateEndpoint(provider.endpoint());
    if (provider.expectedExitIp().isEmpty()) {
      throw new IllegalStateException("proxy provider expected exit IP is required");
    }
    if (!provider
        .expectedExitIp()
        .chars()
        .allMatch(
            character ->
                Character.isDigit(character)
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F')
                    || character == '.'
                    || character == ':')) {
      throw new IllegalStateException("proxy provider expected exit IP must be an IP literal");
    }
    if (!provider.expectedExitIp().contains(".") && !provider.expectedExitIp().contains(":")) {
      throw new IllegalStateException("proxy provider expected exit IP must be an IP literal");
    }
    try {
      var parsed = java.net.InetAddress.getByName(provider.expectedExitIp());
      if (provider.expectedExitIp().contains(".") && !(parsed instanceof java.net.Inet4Address)
          || provider.expectedExitIp().contains(":")
              && !(parsed instanceof java.net.Inet6Address)) {
        throw new IllegalStateException("proxy provider expected exit IP is invalid");
      }
    } catch (java.net.UnknownHostException error) {
      throw new IllegalStateException("proxy provider expected exit IP is invalid", error);
    }
    if (provider.credentialRef().length() > 1024) {
      throw new IllegalStateException("proxy credential reference is too long");
    }
  }

  private record ProviderKey(String providerId, String credentialRef) {}

  private record ProviderDescriptor(
      String providerId, String endpoint, String expectedExitIp, String credentialRef) {}

  private record ProviderConfigDocument(int version, List<ProviderConfigEntry> providers) {}

  private record ProviderConfigEntry(
      String providerId,
      String endpoint,
      String expectedExitIp,
      String credentialRef,
      String exitCheckUrl) {}

  public record RebindTargetSnapshot(
      String sourceBindingProfileId, String targetBindingProfileId, long targetBindingVersion) {}

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
