package io.browsercloud.application;

import io.browsercloud.api.ProxyAllocationView;
import io.browsercloud.api.ProxyOverviewResponse;
import io.browsercloud.api.ProxyProviderView;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.persistence.ProxyAllocationEntity;
import io.browsercloud.persistence.ProxyAllocationJpaRepository;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 单 Provider Static Proxy MVP；未绑定或验证失败时不允许隐式回退直连。 */
@Service
public class StaticProxyApplicationService {

  private static final List<String> ACTIVE_STATES = List.of("ALLOCATED", "BOUND");

  private final ProxyAllocationJpaRepository repository;
  private final SessionRepository sessionRepository;
  private final String providerId;
  private final String endpoint;
  private final String expectedExitIp;
  private final boolean allowDirect;

  public StaticProxyApplicationService(
      ProxyAllocationJpaRepository repository,
      SessionRepository sessionRepository,
      @Value("${proxy.static.provider-id:static-local}") String providerId,
      @Value("${proxy.static.endpoint:}") String endpoint,
      @Value("${proxy.static.expected-exit-ip:}") String expectedExitIp,
      @Value("${proxy.allow-direct:false}") boolean allowDirect,
      @Value("${app.environment:local}") String environment) {
    this.repository = repository;
    this.sessionRepository = sessionRepository;
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
    repository.save(
        new ProxyAllocationEntity(
            allocationId,
            session.tenantId(),
            session.sessionId(),
            providerId,
            endpoint,
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
    if (!expectedExitIp.equals(event.exitIp())) {
      throw new ProxyUnavailableException("Observed proxy exit does not match the allocation");
    }
    allocation.bind(event.exitIp(), event.exitCountry(), event.exitAsn(), Instant.now());
    repository.save(allocation);
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
}
