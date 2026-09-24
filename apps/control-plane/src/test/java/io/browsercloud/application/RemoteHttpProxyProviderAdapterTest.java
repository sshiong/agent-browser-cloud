package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.browsercloud.application.ProxyProviderAdapter.ErrorCode;
import io.browsercloud.application.ProxyProviderAdapter.HealthState;
import io.browsercloud.application.ProxyProviderAdapter.IpFamily;
import io.browsercloud.application.ProxyProviderAdapter.ProductType;
import io.browsercloud.application.ProxyProviderAdapter.Protocol;
import io.browsercloud.application.ProxyProviderAdapter.ProxyAllocationRequest;
import io.browsercloud.application.ProxyProviderAdapter.ProxyProviderException;
import io.browsercloud.application.ProxyProviderAdapter.RotationPolicy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteHttpProxyProviderAdapterTest {

  @TempDir Path tempDir;
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void shouldExecuteTheFullDynamicProviderContractWithBoundedOpaqueCredentials() throws Exception {
    var requests = new ArrayList<RequestEvidence>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          requests.add(
              new RequestEvidence(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                  body));
          var path = exchange.getRequestURI().getPath();
          if (path.endsWith("/capabilities")) {
            json(
                exchange,
                200,
                """
                {"protocols":["HTTP","HTTPS_CONNECT"],"productTypes":["DATACENTER"],
                 "stickySession":true,"countrySelection":true,"citySelection":false,
                 "asnSelection":false,"ipFamilies":["IPV4"],"rotation":true,
                 "bandwidthMetering":true,"providerWebhook":false}
                """);
          } else if (path.endsWith("/allocate")) {
            json(exchange, 201, endpoint("vendor-endpoint-1", "203.0.113.51"));
          } else if (path.endsWith("/health")) {
            json(
                exchange,
                200,
                """
                {"state":"HEALTHY","checkedAt":"2026-09-24T01:00:00Z","reason":"active vendor probe"}
                """);
          } else if (path.endsWith("/rotate")) {
            json(
                exchange,
                200,
                """
                {"previousEndpointId":"vendor-endpoint-1","endpoint":%s}
                """
                    .formatted(endpoint("vendor-endpoint-2", "203.0.113.52")));
          } else if (path.endsWith("/usage")) {
            json(
                exchange,
                200,
                """
                {"metered":true,"ingressBytes":1024,"egressBytes":2048,
                 "measuredAt":"2026-09-24T01:01:00Z"}
                """);
          } else if ("DELETE".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
          } else {
            json(exchange, 404, "{\"code\":\"PROVIDER_OUTAGE\",\"retryable\":false}");
          }
        });
    server.start();
    var adapter = adapter();

    var capabilities = adapter.capabilities();
    assertThat(capabilities.protocols())
        .containsExactlyInAnyOrder(Protocol.HTTP, Protocol.HTTPS_CONNECT);
    assertThat(capabilities.productTypes()).containsExactly(ProductType.DATACENTER);
    assertThat(capabilities.rotation()).isTrue();

    var allocated =
        adapter.allocate(
            new ProxyAllocationRequest(
                "alloc-1",
                "tenant-test",
                "session-test",
                "singapore",
                "SG",
                null,
                null,
                IpFamily.IPV4,
                true));
    assertThat(allocated.endpointId()).isEqualTo("vendor-endpoint-1");
    assertThat(allocated.endpoint()).isEqualTo("http://127.0.0.1:18101");
    assertThat(allocated.expectedExitIp()).isEqualTo("203.0.113.51");
    assertThat(allocated.credentialRef()).isEqualTo("vault://tenant/proxy/dynamic");

    var health = adapter.health("vendor-endpoint-1");
    assertThat(health.state()).isEqualTo(HealthState.HEALTHY);
    assertThat(health.checkedAt()).isEqualTo(Instant.parse("2026-09-24T01:00:00Z"));

    var rotated =
        adapter.rotate(
            "binding-1",
            new RotationPolicy(true, "site challenge", "rotation-request-1", "vendor-endpoint-1"));
    assertThat(rotated.previousEndpointId()).isEqualTo("vendor-endpoint-1");
    assertThat(rotated.endpoint().endpointId()).isEqualTo("vendor-endpoint-2");

    var usage = adapter.usage("vendor-endpoint-2");
    assertThat(usage.metered()).isTrue();
    assertThat(usage.ingressBytes()).isEqualTo(1024);
    assertThat(usage.egressBytes()).isEqualTo(2048);
    adapter.release("vendor-endpoint-2");

    assertThat(requests)
        .allMatch(item -> "Bearer service-token-value".equals(item.authorization()));
    assertThat(requests)
        .filteredOn(item -> item.path().endsWith("/allocate"))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.idempotencyKey()).isEqualTo("alloc-1");
              assertThat(item.body()).contains("vault://tenant/proxy/dynamic");
              assertThat(item.body()).doesNotContain("service-token-value");
            });
    assertThat(requests)
        .filteredOn(item -> item.path().endsWith("/rotate"))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.idempotencyKey()).isEqualTo("rotation-request-1");
              assertThat(item.body())
                  .contains("\"expectedPreviousEndpointId\":\"vendor-endpoint-1\"");
            });
    assertThat(requests)
        .filteredOn(item -> "DELETE".equals(item.method()))
        .singleElement()
        .extracting(RequestEvidence::idempotencyKey)
        .isEqualTo("vendor-endpoint-2");
  }

  @Test
  void shouldPreserveNormalizedProviderErrorsWithoutLeakingProviderBodies() throws Exception {
    var secretBody = "private vendor diagnostic must not escape";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange ->
            json(
                exchange,
                429,
                "{\"code\":\"CAPACITY_EXHAUSTED\",\"retryable\":true,\"detail\":\""
                    + secretBody
                    + "\"}"));
    server.start();

    assertThatThrownBy(
            () ->
                adapter()
                    .allocate(
                        new ProxyAllocationRequest(
                            "alloc-1",
                            "tenant-test",
                            "session-test",
                            null,
                            null,
                            null,
                            null,
                            null,
                            true)))
        .isInstanceOfSatisfying(
            ProxyProviderException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.CAPACITY_EXHAUSTED);
              assertThat(error.retryable()).isTrue();
              assertThat(error.getMessage()).doesNotContain(secretBody);
            });
  }

  @Test
  void shouldRejectMismatchedProviderIdentityAndCredentialReference() throws Exception {
    var response = new AtomicReference<>(endpoint("wrong-provider-endpoint", "203.0.113.51"));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> json(exchange, 200, response.get()));
    server.start();

    response.set(
        endpoint("vendor-endpoint-1", "203.0.113.51")
            .replace("\"providerId\":\"dynamic-provider\"", "\"providerId\":\"other-provider\""));
    assertThatThrownBy(() -> allocate(adapter()))
        .isInstanceOfSatisfying(
            ProxyProviderException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.PROVIDER_OUTAGE));

    response.set(
        endpoint("vendor-endpoint-1", "203.0.113.51")
            .replace("vault://tenant/proxy/dynamic", "vault://other-tenant/proxy/dynamic"));
    assertThatThrownBy(() -> allocate(adapter()))
        .isInstanceOfSatisfying(
            ProxyProviderException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.PROVIDER_OUTAGE));

    response.set(
        endpoint("vendor-endpoint-1", "203.0.113.51")
            .replace("127.0.0.1:18101", "169.254.169.254:80"));
    assertThatThrownBy(() -> allocate(adapter()))
        .isInstanceOfSatisfying(
            ProxyProviderException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.PROVIDER_OUTAGE);
              assertThat(error.getMessage()).contains("host is not allowed");
            });
  }

  @Test
  void shouldAllowPlainHttpOnlyForLoopbackInNonProduction() throws Exception {
    var token = tempDir.resolve("adapter-token");
    Files.writeString(token, "service-token-value\n");
    Files.setPosixFilePermissions(
        token, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));

    assertThatThrownBy(
            () ->
                new RemoteHttpProxyProviderAdapter(
                    "dynamic-provider",
                    "http://127.0.0.1:18080",
                    "vault://tenant/proxy/dynamic",
                    token.toString(),
                    java.util.Set.of("127.0.0.1"),
                    "production"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("URL is invalid");
    assertThatThrownBy(
            () ->
                new RemoteHttpProxyProviderAdapter(
                    "dynamic-provider",
                    "http://192.0.2.10:18080",
                    "vault://tenant/proxy/dynamic",
                    token.toString(),
                    java.util.Set.of("127.0.0.1"),
                    "test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("URL is invalid");

    assertThat(
            new RemoteHttpProxyProviderAdapter(
                    "dynamic-provider",
                    "http://127.0.0.1:18080",
                    "vault://tenant/proxy/dynamic",
                    token.toString(),
                    java.util.Set.of("127.0.0.1"),
                    "test")
                .adapterType())
        .isEqualTo(RemoteHttpProxyProviderAdapter.TYPE);
  }

  private RemoteHttpProxyProviderAdapter adapter() {
    return new RemoteHttpProxyProviderAdapter(
        "dynamic-provider",
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
        "vault://tenant/proxy/dynamic",
        "service-token-value",
        java.util.Set.of("127.0.0.1"),
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        Duration.ofSeconds(5));
  }

  private static ProxyProviderAdapter.ProxyEndpoint allocate(
      RemoteHttpProxyProviderAdapter adapter) {
    return adapter.allocate(
        new ProxyAllocationRequest(
            "alloc-1", "tenant-test", "session-test", null, null, null, null, null, true));
  }

  private static String endpoint(String endpointId, String exitIp) {
    return """
        {"endpointId":"%s","providerId":"dynamic-provider",
         "endpoint":"http://127.0.0.1:18101","expectedExitIp":"%s",
         "credentialRef":"vault://tenant/proxy/dynamic","protocol":"HTTP",
         "productType":"DATACENTER"}
        """
        .formatted(endpointId, exitIp);
  }

  private static void json(HttpExchange exchange, int status, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private record RequestEvidence(
      String method, String path, String authorization, String idempotencyKey, String body) {}
}
