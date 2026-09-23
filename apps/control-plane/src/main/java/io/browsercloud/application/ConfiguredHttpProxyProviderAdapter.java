package io.browsercloud.application;

import static io.browsercloud.application.ProxyProviderAdapter.ErrorCode.GEO_UNAVAILABLE;
import static io.browsercloud.application.ProxyProviderAdapter.ErrorCode.ROTATION_UNSUPPORTED;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

/**
 * Adapter for a fixed HTTP/HTTPS-CONNECT provider endpoint, including commercial endpoints whose
 * opaque credential reference is resolved only by the isolated Network Helper.
 */
final class ConfiguredHttpProxyProviderAdapter implements ProxyProviderAdapter {

  private final String providerId;
  private final String endpoint;
  private final String expectedExitIp;
  private final String credentialRef;
  private final List<String> regions;
  private final ProxyProviderCapabilities capabilities;

  ConfiguredHttpProxyProviderAdapter(
      String providerId,
      String endpoint,
      String expectedExitIp,
      String credentialRef,
      List<String> regions) {
    this.providerId = providerId;
    this.endpoint = endpoint;
    this.expectedExitIp = expectedExitIp;
    this.credentialRef = credentialRef;
    this.regions = List.copyOf(regions);
    this.capabilities =
        new ProxyProviderCapabilities(
            Set.of(Protocol.HTTP, Protocol.HTTPS_CONNECT),
            Set.of(ProductType.DATACENTER),
            true,
            false,
            false,
            false,
            Set.of(ipFamily(expectedExitIp)),
            false,
            false,
            false);
  }

  String providerId() {
    return providerId;
  }

  String endpoint() {
    return endpoint;
  }

  String expectedExitIp() {
    return expectedExitIp;
  }

  String credentialRef() {
    return credentialRef;
  }

  List<String> regions() {
    return regions;
  }

  @Override
  public String adapterType() {
    return "CONFIGURED_HTTP";
  }

  @Override
  public ProxyProviderCapabilities capabilities() {
    return capabilities;
  }

  @Override
  public ProxyEndpoint allocate(ProxyAllocationRequest request) {
    requireToken(request.endpointId(), "endpoint ID");
    requireToken(request.tenantId(), "tenant ID");
    requireToken(request.sessionId(), "session ID");
    if (request.region() != null
        && !request.region().isBlank()
        && !regions.isEmpty()
        && !regions.contains(request.region())) {
      throw new ProxyProviderException(
          GEO_UNAVAILABLE, false, "requested Region is not supported by the proxy provider");
    }
    if (request.country() != null && !request.country().isBlank()
        || request.city() != null && !request.city().isBlank()
        || request.asn() != null && !request.asn().isBlank()) {
      throw new ProxyProviderException(
          GEO_UNAVAILABLE,
          false,
          "configured HTTP provider does not support country, city, or ASN allocation");
    }
    if (request.ipFamily() != null && !capabilities.ipFamilies().contains(request.ipFamily())) {
      throw new ProxyProviderException(
          GEO_UNAVAILABLE, false, "requested IP family is not supported by the proxy provider");
    }
    return new ProxyEndpoint(
        request.endpointId(),
        providerId,
        endpoint,
        expectedExitIp,
        credentialRef,
        Protocol.HTTP,
        ProductType.DATACENTER);
  }

  @Override
  public ProxyHealth health(String endpointId) {
    requireToken(endpointId, "endpoint ID");
    return new ProxyHealth(
        HealthState.UNKNOWN, null, "authoritative Browser Node probe is required");
  }

  @Override
  public RotationResult rotate(String bindingId, RotationPolicy policy) {
    requireToken(bindingId, "binding ID");
    throw new ProxyProviderException(
        ROTATION_UNSUPPORTED, false, "configured HTTP provider does not support rotation");
  }

  @Override
  public void release(String endpointId) {
    requireToken(endpointId, "endpoint ID");
    // The configured endpoint is tenant-managed and not leased from a vendor allocation API.
  }

  @Override
  public ProxyUsage usage(String endpointId) {
    requireToken(endpointId, "endpoint ID");
    return new ProxyUsage(false, 0, 0, null);
  }

  private static IpFamily ipFamily(String address) {
    try {
      return InetAddress.getByName(address) instanceof Inet6Address ? IpFamily.IPV6 : IpFamily.IPV4;
    } catch (UnknownHostException error) {
      throw new IllegalStateException("proxy provider expected exit IP is invalid", error);
    }
  }

  private static void requireToken(String value, String name) {
    if (value == null || value.isBlank() || value.length() > 256) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }

  static void validateEndpoint(String value) {
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
}
