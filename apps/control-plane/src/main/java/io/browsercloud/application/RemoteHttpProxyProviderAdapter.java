package io.browsercloud.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Strict client for an isolated dynamic proxy-provider adapter gateway.
 *
 * <p>The gateway owns vendor-specific OAuth/signing and Secret Manager access. The Control Plane
 * sends only an opaque provider credential reference and a private service credential. Redirects,
 * unbounded responses, non-JSON responses and structurally invalid endpoint identities fail closed.
 */
final class RemoteHttpProxyProviderAdapter implements ProxyProviderAdapter {

  static final String TYPE = "REMOTE_HTTP_V1";
  private static final int MAX_RESPONSE_BYTES = 256 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final String providerId;
  private final URI baseUri;
  private final String credentialRef;
  private final String serviceToken;
  private final Set<String> allowedEndpointHosts;
  private final HttpClient client;
  private final Duration timeout;

  RemoteHttpProxyProviderAdapter(
      String providerId,
      String baseUrl,
      String credentialRef,
      String serviceTokenFile,
      Set<String> allowedEndpointHosts,
      String environment) {
    this(
        providerId,
        validateBaseUri(baseUrl, environment),
        credentialRef,
        readPrivateToken(serviceTokenFile),
        normalizeAllowedEndpointHosts(allowedEndpointHosts),
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        Duration.ofSeconds(15));
  }

  RemoteHttpProxyProviderAdapter(
      String providerId,
      URI baseUri,
      String credentialRef,
      String serviceToken,
      Set<String> allowedEndpointHosts,
      HttpClient client,
      Duration timeout) {
    this.providerId = requireIdentifier(providerId, "provider ID");
    this.baseUri = baseUri;
    this.credentialRef = requireCredentialReference(credentialRef);
    this.serviceToken = requireServiceToken(serviceToken);
    this.allowedEndpointHosts = normalizeAllowedEndpointHosts(allowedEndpointHosts);
    this.client = client;
    if (timeout == null
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
      throw new IllegalArgumentException("proxy provider adapter timeout is invalid");
    }
    this.timeout = timeout;
  }

  @Override
  public String adapterType() {
    return TYPE;
  }

  @Override
  public ProxyProviderCapabilities capabilities() {
    var root = exchange("GET", providerPath("/capabilities"), null, null, Set.of(200));
    return new ProxyProviderCapabilities(
        enumSet(root, "protocols", Protocol.class),
        enumSet(root, "productTypes", ProductType.class),
        requiredBoolean(root, "stickySession"),
        requiredBoolean(root, "countrySelection"),
        requiredBoolean(root, "citySelection"),
        requiredBoolean(root, "asnSelection"),
        enumSet(root, "ipFamilies", IpFamily.class),
        requiredBoolean(root, "rotation"),
        requiredBoolean(root, "bandwidthMetering"),
        requiredBoolean(root, "providerWebhook"));
  }

  @Override
  public ProxyEndpoint allocate(ProxyAllocationRequest request) {
    var endpointId = requireIdentifier(request.endpointId(), "endpoint ID");
    var body =
        Map.ofEntries(
            Map.entry("endpointId", endpointId),
            Map.entry("tenantId", requireIdentifier(request.tenantId(), "tenant ID")),
            Map.entry("sessionId", requireIdentifier(request.sessionId(), "session ID")),
            Map.entry("credentialRef", credentialRef),
            Map.entry("sticky", request.sticky()),
            Map.entry("region", nullable(request.region())),
            Map.entry("country", nullable(request.country())),
            Map.entry("city", nullable(request.city())),
            Map.entry("asn", nullable(request.asn())),
            Map.entry(
                "ipFamily",
                nullable(request.ipFamily() == null ? null : request.ipFamily().name())));
    var root = exchange("POST", providerPath("/allocate"), body, endpointId, Set.of(200, 201));
    return parseEndpoint(root);
  }

  @Override
  public ProxyHealth health(String endpointId) {
    var root = exchange("GET", endpointPath(endpointId, "/health"), null, null, Set.of(200));
    return new ProxyHealth(
        requiredEnum(root, "state", HealthState.class),
        optionalInstant(root, "checkedAt"),
        optionalBoundedText(root, "reason", 512));
  }

  @Override
  public RotationResult rotate(String bindingId, RotationPolicy policy) {
    var safeBindingId = requireIdentifier(bindingId, "binding ID");
    var idempotencyKey = requireIdentifier(policy.idempotencyKey(), "rotation idempotency key");
    var previousEndpointId =
        requireIdentifier(policy.expectedPreviousEndpointId(), "previous endpoint ID");
    var body =
        Map.ofEntries(
            Map.entry("expectedPreviousEndpointId", previousEndpointId),
            Map.entry("preserveGeography", policy.preserveGeography()),
            Map.entry("reason", nullable(policy.reason())),
            Map.entry("credentialRef", credentialRef));
    var root =
        exchange(
            "POST",
            providerPath("/bindings/" + safeBindingId + "/rotate"),
            body,
            idempotencyKey,
            Set.of(200));
    return new RotationResult(
        requireIdentifier(requiredText(root, "previousEndpointId", 256), "endpoint ID"),
        parseEndpoint(requiredObject(root, "endpoint")));
  }

  @Override
  public void release(String endpointId) {
    exchange(
        "DELETE",
        endpointPath(endpointId, ""),
        null,
        requireIdentifier(endpointId, "endpoint ID"),
        Set.of(200, 204));
  }

  @Override
  public ProxyUsage usage(String endpointId) {
    var root = exchange("GET", endpointPath(endpointId, "/usage"), null, null, Set.of(200));
    var ingress = requiredNonNegativeLong(root, "ingressBytes");
    var egress = requiredNonNegativeLong(root, "egressBytes");
    return new ProxyUsage(
        requiredBoolean(root, "metered"), ingress, egress, optionalInstant(root, "measuredAt"));
  }

  private JsonNode exchange(
      String method,
      String path,
      Object body,
      String idempotencyKey,
      Set<Integer> successStatuses) {
    try {
      var builder =
          HttpRequest.newBuilder(baseUri.resolve(path))
              .timeout(timeout)
              .header("Accept", "application/json")
              .header("Authorization", "Bearer " + serviceToken);
      if (idempotencyKey != null) {
        builder.header("Idempotency-Key", idempotencyKey);
      }
      if (body == null) {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        builder
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
      }
      var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      byte[] bytes;
      try (var stream = response.body()) {
        bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
      }
      if (bytes.length > MAX_RESPONSE_BYTES) {
        throw providerFailure(ErrorCode.PROVIDER_OUTAGE, true, "adapter response is too large");
      }
      if (!successStatuses.contains(response.statusCode())) {
        throw parseProviderError(
            response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), bytes);
      }
      if (response.statusCode() == 204 || bytes.length == 0) {
        return JSON.createObjectNode();
      }
      requireJsonContentType(response.headers().firstValue("Content-Type").orElse(""));
      var root = JSON.readTree(bytes);
      if (root == null || !root.isObject()) {
        throw providerFailure(ErrorCode.PROVIDER_OUTAGE, false, "adapter JSON root is invalid");
      }
      return root;
    } catch (ProxyProviderException error) {
      throw error;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw providerFailure(ErrorCode.PROVIDER_OUTAGE, true, "adapter request was interrupted");
    } catch (IOException error) {
      throw providerFailure(ErrorCode.PROVIDER_OUTAGE, true, "adapter request failed");
    }
  }

  private ProxyProviderException parseProviderError(int status, String contentType, byte[] bytes) {
    if (bytes.length > 0
        && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
      try {
        var root = JSON.readTree(bytes);
        var code = requiredEnum(root, "code", ErrorCode.class);
        var retryable = requiredBoolean(root, "retryable");
        return providerFailure(code, retryable, "adapter rejected the operation");
      } catch (RuntimeException | IOException ignored) {
        // Fall through to the status-derived bounded error. Never include the provider body.
      }
    }
    if (status == 401 || status == 403) {
      return providerFailure(ErrorCode.AUTH_FAILED, false, "adapter authentication failed");
    }
    if (status == 429) {
      return providerFailure(ErrorCode.RATE_LIMITED, true, "adapter rate limit was reached");
    }
    return providerFailure(
        ErrorCode.PROVIDER_OUTAGE, status >= 500, "adapter returned an unexpected status");
  }

  private ProxyEndpoint parseEndpoint(JsonNode root) {
    var returnedProvider = requiredText(root, "providerId", 128);
    if (!providerId.equals(returnedProvider)) {
      throw providerFailure(
          ErrorCode.PROVIDER_OUTAGE, false, "adapter provider identity mismatched");
    }
    var returnedCredentialRef = requiredText(root, "credentialRef", 1024);
    if (!credentialRef.equals(returnedCredentialRef)) {
      throw providerFailure(
          ErrorCode.PROVIDER_OUTAGE, false, "adapter credential reference mismatched");
    }
    var endpoint = requiredText(root, "endpoint", 2048);
    try {
      ConfiguredHttpProxyProviderAdapter.validateEndpoint(endpoint);
    } catch (RuntimeException error) {
      throw providerFailure(ErrorCode.PROVIDER_OUTAGE, false, "adapter endpoint is invalid");
    }
    var endpointHost = URI.create(endpoint).getHost().toLowerCase(java.util.Locale.ROOT);
    if (!allowedEndpointHosts.contains(endpointHost)) {
      throw providerFailure(
          ErrorCode.PROVIDER_OUTAGE, false, "adapter endpoint host is not allowed");
    }
    var expectedExitIp = requiredText(root, "expectedExitIp", 64);
    requireIpLiteral(expectedExitIp);
    return new ProxyEndpoint(
        requireIdentifier(requiredText(root, "endpointId", 256), "endpoint ID"),
        returnedProvider,
        endpoint,
        expectedExitIp,
        returnedCredentialRef,
        requiredEnum(root, "protocol", Protocol.class),
        requiredEnum(root, "productType", ProductType.class));
  }

  private String providerPath(String suffix) {
    return "/v1/providers/" + providerId + suffix;
  }

  private String endpointPath(String endpointId, String suffix) {
    return providerPath("/endpoints/" + requireIdentifier(endpointId, "endpoint ID") + suffix);
  }

  private static URI validateBaseUri(String value, String environment) {
    try {
      var uri = URI.create(value);
      var production =
          io.browsercloud.security.DeploymentEnvironment.requiresProductionSecurity(environment);
      var loopback = isLoopbackHost(uri.getHost());
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || uri.getPort() <= 0
          || uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())
          || !"https".equalsIgnoreCase(uri.getScheme())
              && (production || !"http".equalsIgnoreCase(uri.getScheme()) || !loopback)) {
        throw new IllegalStateException("remote proxy adapter URL is invalid");
      }
      return URI.create(uri.getScheme() + "://" + uri.getAuthority() + "/");
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException("remote proxy adapter URL is invalid", error);
    }
  }

  private static boolean isLoopbackHost(String host) {
    if (host == null) {
      return false;
    }
    if ("localhost".equalsIgnoreCase(host)) {
      return true;
    }
    if (!host.matches("[0-9A-Fa-f:.]+") || !host.contains(".") && !host.contains(":")) {
      return false;
    }
    try {
      return InetAddress.getByName(host).isLoopbackAddress();
    } catch (IOException error) {
      return false;
    }
  }

  private static String readPrivateToken(String value) {
    try {
      var path = Path.of(value);
      if (!path.isAbsolute()) {
        throw new IllegalStateException("remote proxy adapter token path must be absolute");
      }
      var attributes =
          Files.readAttributes(
              path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() > 8192) {
        throw new IllegalStateException("remote proxy adapter token file is invalid");
      }
      try {
        var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!Collections.disjoint(
            permissions,
            Set.of(
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE))) {
          throw new IllegalStateException("remote proxy adapter token file is not private");
        }
      } catch (UnsupportedOperationException ignored) {
        // Windows deployments rely on the ACL of the mounted Secret volume.
      }
      var token = Files.readString(path, StandardCharsets.UTF_8);
      if (token.endsWith("\n")) {
        token = token.substring(0, token.length() - 1);
      }
      if (token.contains("\n") || token.contains("\r")) {
        throw new IllegalStateException("remote proxy adapter token must be a single line");
      }
      return requireServiceToken(token);
    } catch (IllegalStateException error) {
      throw error;
    } catch (IOException | RuntimeException error) {
      throw new IllegalStateException("cannot read remote proxy adapter token", error);
    }
  }

  private static String requireServiceToken(String value) {
    if (value == null || value.isBlank() || value.length() > 4096) {
      throw new IllegalArgumentException("remote proxy adapter service token is invalid");
    }
    return value;
  }

  private static String requireCredentialReference(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 1024
        || !value.matches("^(vault|secret|aws-sm|gcp-sm|azure-kv)://[^\\s]+$")) {
      throw new IllegalArgumentException("proxy provider credential reference is invalid");
    }
    return value;
  }

  private static Set<String> normalizeAllowedEndpointHosts(Set<String> values) {
    if (values == null || values.isEmpty() || values.size() > 64) {
      throw new IllegalArgumentException("proxy adapter endpoint host allowlist is invalid");
    }
    var normalized = new java.util.HashSet<String>();
    for (var value : values) {
      if (value == null
          || value.isBlank()
          || value.length() > 253
          || value.contains("/")
          || value.contains("*")
          || !value.matches("[A-Za-z0-9.:-]+")) {
        throw new IllegalArgumentException("proxy adapter endpoint host allowlist is invalid");
      }
      normalized.add(value.toLowerCase(java.util.Locale.ROOT));
    }
    if (normalized.size() != values.size()) {
      throw new IllegalArgumentException("proxy adapter endpoint host allowlist is invalid");
    }
    return Set.copyOf(normalized);
  }

  private static String requireIdentifier(String value, String name) {
    if (value == null
        || value.isBlank()
        || value.length() > 256
        || !value.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value;
  }

  private static void requireIpLiteral(String value) {
    try {
      if (!value.matches("[0-9A-Fa-f:.]+") || !value.contains(".") && !value.contains(":")) {
        throw new IllegalArgumentException("adapter exit IP is invalid");
      }
      var parsed = InetAddress.getByName(value);
      if (value.contains(".") && !(parsed instanceof Inet4Address)
          || value.contains(":") && !(parsed instanceof Inet6Address)) {
        throw new IllegalArgumentException("adapter exit IP is invalid");
      }
    } catch (IOException error) {
      throw new IllegalArgumentException("adapter exit IP is invalid", error);
    }
  }

  private static JsonNode requiredObject(JsonNode root, String field) {
    var value = root == null ? null : root.get(field);
    if (value == null || !value.isObject()) {
      throw providerFailure(ErrorCode.PROVIDER_OUTAGE, false, "adapter object field is invalid");
    }
    return value;
  }

  private static String requiredText(JsonNode root, String field, int maxLength) {
    var value = root == null ? null : root.get(field);
    if (value == null
        || !value.isTextual()
        || value.textValue().isBlank()
        || value.textValue().length() > maxLength) {
      throw providerFailure(ErrorCode.PROVIDER_OUTAGE, false, "adapter text field is invalid");
    }
    return value.textValue();
  }

  private static String optionalBoundedText(JsonNode root, String field, int maxLength) {
    var value = root == null ? null : root.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual() || value.textValue().length() > maxLength) {
      throw providerFailure(ErrorCode.PROVIDER_OUTAGE, false, "adapter text field is invalid");
    }
    return value.textValue();
  }

  private static boolean requiredBoolean(JsonNode root, String field) {
    var value = root == null ? null : root.get(field);
    if (value == null || !value.isBoolean()) {
      throw providerFailure(ErrorCode.PROVIDER_OUTAGE, false, "adapter boolean field is invalid");
    }
    return value.booleanValue();
  }

  private static long requiredNonNegativeLong(JsonNode root, String field) {
    var value = root == null ? null : root.get(field);
    if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
      throw providerFailure(ErrorCode.PROVIDER_OUTAGE, false, "adapter counter field is invalid");
    }
    return value.longValue();
  }

  private static Instant optionalInstant(JsonNode root, String field) {
    var value = root == null ? null : root.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw providerFailure(ErrorCode.PROVIDER_OUTAGE, false, "adapter timestamp field is invalid");
    }
    try {
      return Instant.parse(value.textValue());
    } catch (RuntimeException error) {
      throw providerFailure(ErrorCode.PROVIDER_OUTAGE, false, "adapter timestamp field is invalid");
    }
  }

  private static <T extends Enum<T>> T requiredEnum(JsonNode root, String field, Class<T> type) {
    var value = requiredText(root, field, 64);
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException error) {
      throw providerFailure(ErrorCode.PROVIDER_OUTAGE, false, "adapter enum field is invalid");
    }
  }

  private static <T extends Enum<T>> Set<T> enumSet(JsonNode root, String field, Class<T> type) {
    var value = root == null ? null : root.get(field);
    if (value == null || !value.isArray() || value.isEmpty() || value.size() > 32) {
      throw providerFailure(
          ErrorCode.PROVIDER_OUTAGE, false, "adapter capability field is invalid");
    }
    var result = java.util.EnumSet.noneOf(type);
    value.forEach(
        item -> {
          if (!item.isTextual()) {
            throw providerFailure(
                ErrorCode.PROVIDER_OUTAGE, false, "adapter capability field is invalid");
          }
          try {
            result.add(Enum.valueOf(type, item.textValue()));
          } catch (IllegalArgumentException error) {
            throw providerFailure(
                ErrorCode.PROVIDER_OUTAGE, false, "adapter capability field is invalid");
          }
        });
    return Set.copyOf(result);
  }

  private static void requireJsonContentType(String value) {
    if (!value.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
      throw providerFailure(ErrorCode.PROVIDER_OUTAGE, false, "adapter content type is invalid");
    }
  }

  private static Object nullable(Object value) {
    return value == null ? "" : value;
  }

  private static ProxyProviderException providerFailure(
      ErrorCode code, boolean retryable, String message) {
    return new ProxyProviderException(code, retryable, message);
  }
}
