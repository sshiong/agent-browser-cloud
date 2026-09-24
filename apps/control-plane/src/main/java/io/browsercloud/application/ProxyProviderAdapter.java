package io.browsercloud.application;

import java.time.Instant;
import java.util.Set;

/**
 * Normalized control-plane boundary for proxy vendors.
 *
 * <p>Implementations receive credential references, never credential material. Provider webhooks
 * are hints only; callers must still obtain authoritative health evidence from Browser Node probes.
 * Allocate and release implementations must be idempotent for the supplied endpoint identity so a
 * transaction retry cannot leak or double-release a vendor resource.
 */
public interface ProxyProviderAdapter {

  String adapterType();

  ProxyProviderCapabilities capabilities();

  ProxyEndpoint allocate(ProxyAllocationRequest request);

  ProxyHealth health(String endpointId);

  RotationResult rotate(String bindingId, RotationPolicy policy);

  void release(String endpointId);

  ProxyUsage usage(String endpointId);

  enum Protocol {
    HTTP,
    HTTPS_CONNECT,
    SOCKS5
  }

  enum ProductType {
    RESIDENTIAL,
    ISP,
    DATACENTER,
    MOBILE
  }

  enum IpFamily {
    IPV4,
    IPV6
  }

  enum HealthState {
    UNKNOWN,
    HEALTHY,
    UNHEALTHY
  }

  enum ErrorCode {
    CAPACITY_EXHAUSTED,
    AUTH_FAILED,
    GEO_UNAVAILABLE,
    RATE_LIMITED,
    ENDPOINT_UNHEALTHY,
    PROVIDER_OUTAGE,
    ROTATION_UNSUPPORTED,
    RELEASE_FAILED
  }

  record ProxyProviderCapabilities(
      Set<Protocol> protocols,
      Set<ProductType> productTypes,
      boolean stickySession,
      boolean countrySelection,
      boolean citySelection,
      boolean asnSelection,
      Set<IpFamily> ipFamilies,
      boolean rotation,
      boolean bandwidthMetering,
      boolean providerWebhook) {
    public ProxyProviderCapabilities {
      protocols = Set.copyOf(protocols);
      productTypes = Set.copyOf(productTypes);
      ipFamilies = Set.copyOf(ipFamilies);
    }
  }

  record ProxyAllocationRequest(
      String endpointId,
      String tenantId,
      String sessionId,
      String region,
      String country,
      String city,
      String asn,
      IpFamily ipFamily,
      boolean sticky) {}

  record ProxyEndpoint(
      String endpointId,
      String providerId,
      String endpoint,
      String expectedExitIp,
      String credentialRef,
      Protocol protocol,
      ProductType productType) {}

  record ProxyHealth(HealthState state, Instant checkedAt, String reason) {}

  /**
   * A rotation request bound to one durable control-plane workflow.
   *
   * <p>The idempotency key must remain stable when the workflow is reconciled after a crash, but
   * must differ for later rotations of the same logical binding.
   */
  record RotationPolicy(
      boolean preserveGeography,
      String reason,
      String idempotencyKey,
      String expectedPreviousEndpointId) {}

  record RotationResult(String previousEndpointId, ProxyEndpoint endpoint) {}

  record ProxyUsage(boolean metered, long ingressBytes, long egressBytes, Instant measuredAt) {}

  final class ProxyProviderException extends RuntimeException {
    private final ErrorCode code;
    private final boolean retryable;

    public ProxyProviderException(ErrorCode code, boolean retryable, String message) {
      super(message);
      this.code = code;
      this.retryable = retryable;
    }

    public ErrorCode code() {
      return code;
    }

    public boolean retryable() {
      return retryable;
    }
  }
}
