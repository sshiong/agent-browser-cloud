package io.browsercloud.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.ProxyAllocationView;
import io.browsercloud.api.ProxyBindingModels.ProxyBindingListResponse;
import io.browsercloud.api.ProxyBindingModels.ProxyBindingRequest;
import io.browsercloud.api.ProxyBindingModels.ProxyBindingView;
import io.browsercloud.api.ProxyBindingModels.ProxyRoutingCandidateScore;
import io.browsercloud.api.ProxyBindingModels.ProxyRoutingDecision;
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
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Comparator;
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
  private static final long HEALTH_FRESHNESS_SECONDS = 90;

  private final ProxyAllocationJpaRepository repository;
  private final ProxyBindingProfileJpaRepository bindingProfiles;
  private final SessionProxyBindingAssignmentJpaRepository bindingAssignments;
  private final SessionRepository sessionRepository;
  private final IdempotencyService idempotency;
  private final AuditApplicationService audit;
  private final ProxyBindingHealthApplicationService bindingHealth;
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
      ProxyBindingHealthApplicationService bindingHealth,
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
    this.bindingHealth = bindingHealth;
    var fallbackProvider =
        new ProviderDescriptor(
            requireIdentifier(providerId, "proxy provider ID"),
            endpoint.trim(),
            expectedExitIp.trim(),
            credentialRef == null ? "" : credentialRef.trim(),
            List.of(),
            BigDecimal.ZERO,
            50,
            10000);
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

  /** Compatibility constructor used by isolated unit tests without a Spring JDBC context. */
  StaticProxyApplicationService(
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
      String providerConfigFile,
      boolean allowDirect,
      String environment) {
    this(
        repository,
        bindingProfiles,
        bindingAssignments,
        sessionRepository,
        idempotency,
        audit,
        null,
        providerId,
        endpoint,
        expectedExitIp,
        credentialRef,
        providerConfigFile,
        allowDirect,
        environment);
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
                entry.credentialRef() == null ? "" : entry.credentialRef().strip(),
                normalizeRegions(entry.regions()),
                entry.costPerGibUsd() == null ? BigDecimal.ZERO : entry.costPerGibUsd(),
                entry.reputationScore() == null ? 50 : entry.reputationScore(),
                entry.maxConcurrentSessions() == null ? 10000 : entry.maxConcurrentSessions());
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
      if (bindingHealth == null) {
        bindingProfiles
            .findByBindingProfileIdAndTenantId(
                allocation.getBindingProfileId(), allocation.getTenantId())
            .ifPresent(
                profile -> {
                  profile.markHealthy(event.exitIp(), now);
                  bindingProfiles.save(profile);
                });
      } else {
        bindingHealth.recordRuntimeVerified(allocation, event.nodeId(), event.exitIp(), now);
      }
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
    var providerViews =
        providers.values().stream()
            .sorted(
                Comparator.comparing(ProviderDescriptor::providerId)
                    .thenComparing(ProviderDescriptor::credentialRef))
            .map(this::toProviderView)
            .toList();
    var provider =
        providerViews.size() == 1
            ? providerViews.getFirst()
            : new ProxyProviderView(
                providerId,
                providers.size() > 1 ? "STATIC_HTTP_CATALOG" : "STATIC_HTTP",
                endpoint,
                expectedExitIp,
                allowDirect,
                providers.isEmpty() ? "UNCONFIGURED" : "CATALOG_CONFIGURED",
                List.of(),
                BigDecimal.ZERO,
                0,
                Math.max(
                    1,
                    providers.values().stream()
                        .mapToInt(ProviderDescriptor::maxConcurrentSessions)
                        .sum()));
    return new ProxyOverviewResponse(provider, providerViews, allocations, allocations.size());
  }

  private ProxyProviderView toProviderView(ProviderDescriptor provider) {
    return new ProxyProviderView(
        provider.providerId(),
        "STATIC_HTTP",
        provider.endpoint(),
        provider.expectedExitIp(),
        allowDirect,
        "CONFIGURED",
        provider.regions(),
        provider.costPerGibUsd(),
        provider.reputationScore(),
        provider.maxConcurrentSessions());
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
    var provider =
        requireConfiguredProvider(
            request.providerId(), request.credentialRef(), request.expectedExitIp());
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
                provider.costPerGibUsd(),
                provider.reputationScore(),
                provider.maxConcurrentSessions(),
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
    var provider =
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
        provider.costPerGibUsd(),
        provider.reputationScore(),
        provider.maxConcurrentSessions(),
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
      if (providers.size() > 1) {
        assignAutomaticBindingProfile(session, sessionRegion, actorId, Instant.now());
      }
      return;
    }
    var profile = requireBindingForAssignment(bindingProfileId, session.tenantId());
    if (!profile.isEnabled()) {
      throw new ProxyBindingRejectedException("BINDING_IS_DISABLED");
    }
    if (profile.getRegion() != null && !profile.getRegion().equals(sessionRegion)) {
      throw new ProxyBindingRejectedException("BINDING_REGION_MISMATCH");
    }
    var provider =
        requireConfiguredProvider(
            profile.getProviderId(), profile.getCredentialRef(), profile.getExpectedExitIp());
    requireProviderRegion(provider, sessionRegion);
    requireProviderCapacity(session.tenantId(), profile, null);
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

  private void assignAutomaticBindingProfile(
      SessionContext session, String sessionRegion, String actorId, Instant now) {
    var candidates =
        bindingProfiles.findAllForAutomaticRouting(session.tenantId()).stream()
            .filter(ProxyBindingProfileEntity::isEnabled)
            .filter(profile -> "HEALTHY".equals(profile.getHealthState()))
            .filter(
                profile ->
                    profile.getLastHealthCheckedAt() != null
                        && !profile
                            .getLastHealthCheckedAt()
                            .isBefore(now.minusSeconds(HEALTH_FRESHNESS_SECONDS)))
            .filter(
                profile -> profile.getRegion() == null || profile.getRegion().equals(sessionRegion))
            .map(profile -> automaticCandidate(session.tenantId(), sessionRegion, profile))
            .flatMap(java.util.Optional::stream)
            .toList();
    if (candidates.isEmpty()) {
      throw new ProxyUnavailableException("NO_HEALTHY_PROXY_ROUTE");
    }
    var minimumCost =
        candidates.stream()
            .map(candidate -> candidate.profile().getCostPerGibUsd())
            .min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    var maximumCost =
        candidates.stream()
            .map(candidate -> candidate.profile().getCostPerGibUsd())
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    var scored =
        candidates.stream()
            .map(candidate -> scoreCandidate(candidate, sessionRegion, minimumCost, maximumCost))
            .toList();
    var ranked =
        scored.stream()
            .sorted(
                Comparator.comparingDouble(AutoRouteSelection::routingScore)
                    .reversed()
                    .thenComparing(
                        Comparator.comparingInt(AutoRouteSelection::qualityScore).reversed())
                    .thenComparing(selection -> selection.candidate().profile().getCostPerGibUsd())
                    .thenComparing(
                        selection -> selection.candidate().profile().getBindingProfileId()))
            .findFirst()
            .orElseThrow();
    var candidateScores =
        scored.stream()
            .sorted(Comparator.comparingDouble(AutoRouteSelection::routingScore).reversed())
            .map(StaticProxyApplicationService::candidateScoreEvidence)
            .toList();
    var profile = ranked.candidate().profile();
    bindingAssignments.save(
        SessionProxyBindingAssignmentEntity.automatic(
            session.sessionId(),
            session.tenantId(),
            profile.getBindingProfileId(),
            profile.getVersion(),
            profile.getProviderId(),
            profile.getRegion(),
            profile.getExpectedExitIp(),
            profile.getCredentialRef(),
            actorId,
            now,
            ranked.routingScore(),
            ranked.qualityScore(),
            profile.getReputationScore(),
            profile.getCostPerGibUsd(),
            Math.toIntExact(ranked.candidate().activeReservations()),
            profile.getMaxConcurrentSessions(),
            candidateScores));
    appendBindingAudit(
        session.tenantId(),
        actorId,
        profile.getBindingProfileId(),
        "SESSION_PROXY_ROUTE_SELECTED",
        "proxy-route-" + session.sessionId(),
        Map.of(
            "sessionId", session.sessionId(),
            "providerId", profile.getProviderId(),
            "region", profile.getRegion() == null ? "ANY" : profile.getRegion(),
            "routingScore", ranked.routingScore(),
            "qualityScore", ranked.qualityScore(),
            "reputationScore", profile.getReputationScore(),
            "costPerGibUsd", profile.getCostPerGibUsd(),
            "activeReservations", ranked.candidate().activeReservations(),
            "maxConcurrentSessions", profile.getMaxConcurrentSessions(),
            "candidateScores", candidateScores));
  }

  private java.util.Optional<AutoRouteCandidate> automaticCandidate(
      String tenantId, String sessionRegion, ProxyBindingProfileEntity profile) {
    ProviderDescriptor provider;
    try {
      provider =
          requireConfiguredProvider(
              profile.getProviderId(), profile.getCredentialRef(), profile.getExpectedExitIp());
    } catch (ProxyBindingRejectedException ignored) {
      return java.util.Optional.empty();
    }
    if (!provider.regions().isEmpty() && !provider.regions().contains(sessionRegion)) {
      return java.util.Optional.empty();
    }
    var active =
        bindingAssignments.countActiveProviderReservations(
            tenantId, profile.getProviderId(), profile.getCredentialRef());
    if (active >= profile.getMaxConcurrentSessions()) {
      return java.util.Optional.empty();
    }
    var quality = proxyQualityScore(profile);
    if (quality == null) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(new AutoRouteCandidate(profile, provider, active, quality));
  }

  private static AutoRouteSelection scoreCandidate(
      AutoRouteCandidate candidate,
      String sessionRegion,
      BigDecimal minimumCost,
      BigDecimal maximumCost) {
    var profile = candidate.profile();
    var costRange = maximumCost.subtract(minimumCost);
    var costScore =
        costRange.signum() == 0
            ? 100.0
            : maximumCost
                .subtract(profile.getCostPerGibUsd())
                .multiply(BigDecimal.valueOf(100))
                .divide(costRange, 6, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    var regionScore = sessionRegion.equals(profile.getRegion()) ? 100.0 : 60.0;
    var headroomScore =
        100.0
            * (profile.getMaxConcurrentSessions() - candidate.activeReservations())
            / profile.getMaxConcurrentSessions();
    var routingScore =
        candidate.qualityScore() * 0.45
            + profile.getReputationScore() * 0.20
            + costScore * 0.15
            + regionScore * 0.10
            + headroomScore * 0.10;
    return new AutoRouteSelection(
        candidate, candidate.qualityScore(), costScore, regionScore, headroomScore, routingScore);
  }

  private static Map<String, Object> candidateScoreEvidence(AutoRouteSelection selection) {
    var profile = selection.candidate().profile();
    return Map.ofEntries(
        Map.entry("bindingProfileId", profile.getBindingProfileId()),
        Map.entry("providerId", profile.getProviderId()),
        Map.entry("routingScore", selection.routingScore()),
        Map.entry("qualityScore", selection.qualityScore()),
        Map.entry("reputationScore", profile.getReputationScore()),
        Map.entry("costPerGibUsd", profile.getCostPerGibUsd()),
        Map.entry("costScore", selection.costScore()),
        Map.entry("regionScore", selection.regionScore()),
        Map.entry("headroomScore", selection.headroomScore()),
        Map.entry("activeReservations", selection.candidate().activeReservations()),
        Map.entry("maxConcurrentSessions", profile.getMaxConcurrentSessions()));
  }

  @Transactional(readOnly = true)
  public ProxyRoutingDecision assignedRoutingDecision(String sessionId, String tenantId) {
    return bindingAssignments
        .findBySessionIdAndTenantId(sessionId, tenantId)
        .map(assignment -> toRoutingDecision(assignment, true))
        .orElse(null);
  }

  @Transactional(readOnly = true)
  public Map<String, ProxyRoutingDecision> assignedRoutingDecisions(
      java.util.Collection<String> sessionIds, String tenantId) {
    if (sessionIds.isEmpty()) {
      return Map.of();
    }
    return bindingAssignments.findAllByTenantIdAndSessionIdIn(tenantId, sessionIds).stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                SessionProxyBindingAssignmentEntity::getSessionId,
                assignment -> toRoutingDecision(assignment, false)));
  }

  private ProxyRoutingDecision toRoutingDecision(
      SessionProxyBindingAssignmentEntity assignment, boolean includeCandidateScores) {
    var storedCandidates = assignment.getCandidateScores();
    var candidates =
        !includeCandidateScores || storedCandidates == null
            ? List.<ProxyRoutingCandidateScore>of()
            : storedCandidates.stream()
                .map(StaticProxyApplicationService::toRoutingCandidateScore)
                .toList();
    return new ProxyRoutingDecision(
        assignment.getSessionId(),
        assignment.getBindingProfileId(),
        assignment.getProviderId(),
        assignment.getSelectionMode(),
        assignment.getRoutingScore(),
        assignment.getQualityScore(),
        assignment.getReputationScore(),
        assignment.getCostPerGibUsd(),
        assignment.getActiveReservations(),
        assignment.getMaxConcurrentSessions(),
        storedCandidates == null ? 0 : storedCandidates.size(),
        candidates,
        assignment.getAssignedAt());
  }

  private static ProxyRoutingCandidateScore toRoutingCandidateScore(Map<String, Object> value) {
    return new ProxyRoutingCandidateScore(
        String.valueOf(value.get("bindingProfileId")),
        String.valueOf(value.get("providerId")),
        number(value, "routingScore").doubleValue(),
        number(value, "qualityScore").intValue(),
        number(value, "reputationScore").intValue(),
        new BigDecimal(String.valueOf(value.get("costPerGibUsd"))),
        number(value, "costScore").doubleValue(),
        number(value, "regionScore").doubleValue(),
        number(value, "headroomScore").doubleValue(),
        number(value, "activeReservations").intValue(),
        number(value, "maxConcurrentSessions").intValue());
  }

  private static Number number(Map<String, Object> value, String key) {
    var raw = value.get(key);
    if (raw instanceof Number number) {
      return number;
    }
    throw new IllegalStateException("persisted proxy routing evidence is invalid");
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
    var provider =
        requireConfiguredProvider(
            profile.getProviderId(), profile.getCredentialRef(), profile.getExpectedExitIp());
    requireProviderRegion(provider, sessionRegion);
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
    var profile = requireBindingForAssignment(targetBindingProfileId, tenantId);
    if (!profile.isEnabled() || profile.getVersion() != expectedBindingVersion) {
      throw new ProxyBindingRejectedException("TARGET_BINDING_CHANGED");
    }
    if (profile.getRegion() != null && !profile.getRegion().equals(sessionRegion)) {
      throw new ProxyBindingRejectedException("BINDING_REGION_MISMATCH");
    }
    var provider =
        requireConfiguredProvider(
            profile.getProviderId(), profile.getCredentialRef(), profile.getExpectedExitIp());
    requireProviderRegion(provider, sessionRegion);
    requireProviderCapacity(
        tenantId,
        profile,
        bindingAssignments.findBySessionIdAndTenantId(sessionId, tenantId).orElse(null));
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
    var provider =
        requireConfiguredProvider(
            request.providerId(), request.credentialRef(), request.expectedExitIp());
    if (request.region() != null && !request.region().isBlank()) {
      requireProviderRegion(provider, request.region());
    }
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

  private static void requireProviderRegion(ProviderDescriptor provider, String region) {
    if (!provider.regions().isEmpty() && !provider.regions().contains(region)) {
      throw new ProxyBindingRejectedException("PROVIDER_REGION_NOT_SUPPORTED");
    }
  }

  private ProxyBindingProfileEntity requireBinding(String bindingProfileId, String tenantId) {
    return bindingProfiles
        .findByBindingProfileIdAndTenantId(bindingProfileId, tenantId)
        .orElseThrow(() -> new ProxyBindingNotFoundException(bindingProfileId));
  }

  private ProxyBindingProfileEntity requireBindingForAssignment(
      String bindingProfileId, String tenantId) {
    return bindingProfiles
        .findForAssignment(bindingProfileId, tenantId)
        .orElseThrow(() -> new ProxyBindingNotFoundException(bindingProfileId));
  }

  private void requireProviderCapacity(
      String tenantId,
      ProxyBindingProfileEntity profile,
      SessionProxyBindingAssignmentEntity replacedAssignment) {
    var reservations =
        bindingAssignments.countActiveProviderReservations(
            tenantId, profile.getProviderId(), profile.getCredentialRef());
    if (replacedAssignment != null
        && replacedAssignment.getProviderId().equals(profile.getProviderId())
        && replacedAssignment.getCredentialRef().equals(profile.getCredentialRef())) {
      reservations--;
    }
    if (reservations >= profile.getMaxConcurrentSessions()) {
      throw new ProxyBindingRejectedException("PROVIDER_CAPACITY_EXHAUSTED");
    }
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
    var healthFreshUntil =
        profile.getLastHealthCheckedAt() == null
            ? null
            : profile.getLastHealthCheckedAt().plusSeconds(HEALTH_FRESHNESS_SECONDS);
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
        profile.getProbeSuccessCount() + profile.getProbeFailureCount(),
        probeSuccessRate(profile),
        profile.getProbeLatencyEwmaMs(),
        proxyQualityScore(profile),
        profile.getCostPerGibUsd(),
        profile.getReputationScore(),
        profile.getMaxConcurrentSessions(),
        profile.isEnabled()
            && "HEALTHY".equals(profile.getHealthState())
            && healthFreshUntil != null
            && !healthFreshUntil.isBefore(Instant.now()),
        healthFreshUntil,
        profile.getConsecutiveProbeFailures(),
        profile.getVersion(),
        profile.getCreatedBy(),
        profile.getCreatedAt(),
        profile.getUpdatedAt());
  }

  private static Double probeSuccessRate(ProxyBindingProfileEntity profile) {
    var total = profile.getProbeSuccessCount() + profile.getProbeFailureCount();
    if (total == 0) {
      return null;
    }
    return Math.round(profile.getProbeSuccessCount() * 10_000.0 / total) / 100.0;
  }

  /** 80% availability EWMA + 20% latency EWMA, with 2 seconds treated as exhausted quality. */
  private static Integer proxyQualityScore(ProxyBindingProfileEntity profile) {
    var success = profile.getProbeSuccessEwma();
    if (success == null || "DISABLED".equals(profile.getHealthState())) {
      return null;
    }
    var latency = profile.getProbeLatencyEwmaMs();
    var latencyScore = latency == null ? 100.0 : Math.max(0.0, 100.0 - latency / 20.0);
    var score = (int) Math.round(success * 80.0 + latencyScore * 0.2);
    return "UNHEALTHY".equals(profile.getHealthState()) ? Math.min(score, 25) : score;
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
    if (provider.costPerGibUsd().signum() < 0
        || provider.costPerGibUsd().compareTo(BigDecimal.valueOf(10000)) > 0) {
      throw new IllegalStateException("proxy provider cost must be between 0 and 10000 USD/GiB");
    }
    if (provider.reputationScore() < 0 || provider.reputationScore() > 100) {
      throw new IllegalStateException("proxy provider reputation score must be between 0 and 100");
    }
    if (provider.maxConcurrentSessions() < 1 || provider.maxConcurrentSessions() > 1000000) {
      throw new IllegalStateException(
          "proxy provider max concurrent Sessions must be between 1 and 1000000");
    }
  }

  private static List<String> normalizeRegions(List<String> regions) {
    if (regions == null || regions.isEmpty()) {
      return List.of();
    }
    var normalized =
        regions.stream()
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .distinct()
            .sorted()
            .toList();
    if (normalized.size() != regions.size()
        || normalized.size() > 64
        || normalized.stream()
            .anyMatch(
                value ->
                    value.length() > 32
                        || !value
                            .chars()
                            .allMatch(
                                character ->
                                    Character.isLowerCase(character)
                                        || Character.isDigit(character)
                                        || character == '-'))) {
      throw new IllegalStateException("proxy provider regions are invalid");
    }
    return normalized;
  }

  private record ProviderKey(String providerId, String credentialRef) {}

  private record ProviderDescriptor(
      String providerId,
      String endpoint,
      String expectedExitIp,
      String credentialRef,
      List<String> regions,
      BigDecimal costPerGibUsd,
      int reputationScore,
      int maxConcurrentSessions) {}

  private record ProviderConfigDocument(int version, List<ProviderConfigEntry> providers) {}

  private record ProviderConfigEntry(
      String providerId,
      String endpoint,
      String expectedExitIp,
      String credentialRef,
      String exitCheckUrl,
      List<String> regions,
      BigDecimal costPerGibUsd,
      Integer reputationScore,
      Integer maxConcurrentSessions) {}

  private record AutoRouteCandidate(
      ProxyBindingProfileEntity profile,
      ProviderDescriptor provider,
      long activeReservations,
      int qualityScore) {}

  private record AutoRouteSelection(
      AutoRouteCandidate candidate,
      int qualityScore,
      double costScore,
      double regionScore,
      double headroomScore,
      double routingScore) {}

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
