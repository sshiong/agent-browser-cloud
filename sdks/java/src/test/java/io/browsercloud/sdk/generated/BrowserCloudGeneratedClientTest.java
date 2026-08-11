package io.browsercloud.sdk.generated;

import java.util.List;
import java.util.Map;

public final class BrowserCloudGeneratedClientTest {
  public static void main(String[] args) {
    generatedSurfaceAndRuntimeRequest();
    queryAllowlistAndStructuredError();
  }

  private static void generatedSurfaceAndRuntimeRequest() {
    BrowserCloudGeneratedClient.Transport transport =
        (method, uri, headers, body) -> {
          require(method.equals("GET"), "expected GET");
          require(uri.toString().equals("https://browser.example/api/v1/sessions/ses_1"), "bad URI");
          require(headers.get("X-Tenant-Id").equals("tenant-a"), "missing tenant");
          require(headers.get("X-Actor-Id").equals("actor-a"), "missing actor");
          return new BrowserCloudGeneratedClient.Response(
              200, Map.of("content-type", "application/json"), "{\"sessionId\":\"ses_1\"}");
        };
    var client =
        new BrowserCloudGeneratedClient(
            "https://browser.example", "tenant-a", null, "actor-a", transport);
    var response =
        client.getSession(
            new BrowserCloudGeneratedClient.Request(
                Map.of("sessionId", "ses_1"), Map.of(), Map.of(), null));
    require(BrowserCloudGeneratedClient.OPERATIONS.size() == 194, "operation coverage drifted");
    require(response.body().contains("ses_1"), "response missing");
    Models.SessionView session = null;
    Models.ProxyRoutingDecision routing = null;
    Models.RuntimeValidationJobClaim validationClaim = null;
    require(
        session == null && routing == null && validationClaim == null,
        "generated models are unavailable");
  }

  private static void queryAllowlistAndStructuredError() {
    var client =
        new BrowserCloudGeneratedClient(
            "https://browser.example",
            "tenant-a",
            null,
            null,
            (method, uri, headers, body) ->
                new BrowserCloudGeneratedClient.Response(
                    409,
                    Map.of("content-type", "application/json"),
                    "{\"code\":\"VERSION_CONFLICT\",\"message\":\"conflict\",\"requestId\":\"req-1\"}"));
    try {
      client.listSessions(
          new BrowserCloudGeneratedClient.Request(
              Map.of(), Map.of("notInContract", List.of("value")), Map.of(), null));
      throw new AssertionError("expected query rejection");
    } catch (IllegalArgumentException expected) {
      require(expected.getMessage().contains("unknown query parameter"), "wrong rejection");
    }
    try {
      client.getSession(
          new BrowserCloudGeneratedClient.Request(
              Map.of("sessionId", "ses_1"),
              Map.of(),
              Map.of("X-Tenant-Id", "tenant-b"),
              null));
      throw new AssertionError("expected identity header rejection");
    } catch (IllegalArgumentException expected) {
      require(expected.getMessage().contains("identity-controlled header"), "wrong header rejection");
    }
    try {
      client.createSession(
          new BrowserCloudGeneratedClient.Request(
              Map.of(), Map.of(), Map.of("Idempotency-Key", "idem-1"), null));
      throw new AssertionError("expected required body rejection");
    } catch (IllegalArgumentException expected) {
      require(expected.getMessage().contains("request body is required"), "wrong body rejection");
    }
    try {
      client.getSession(
          new BrowserCloudGeneratedClient.Request(
              Map.of("sessionId", "ses_1"), Map.of(), Map.of(), null));
      throw new AssertionError("expected API exception");
    } catch (BrowserCloudGeneratedClient.ApiException expected) {
      require(expected.status() == 409, "status not preserved");
      require(expected.code().equals("VERSION_CONFLICT"), "code not preserved");
      require(expected.requestId().equals("req-1"), "request ID not preserved");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
