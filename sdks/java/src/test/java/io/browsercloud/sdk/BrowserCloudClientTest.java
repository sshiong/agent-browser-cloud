package io.browsercloud.sdk;

import java.net.URI;
import java.util.List;
import java.util.Map;

public final class BrowserCloudClientTest {

  public static void main(String[] args) {
    createMediaSessionPreservesIdentityAndIdempotency();
    structuredApiErrorIsPreserved();
  }

  private static void createMediaSessionPreservesIdentityAndIdempotency() {
    BrowserCloudClient.Transport transport =
        (method, uri, headers, body) -> {
          require(method.equals("POST"), "expected POST");
          require(uri.getPath().equals("/api/v1/sessions"), "unexpected path");
          require(headers.get("X-Tenant-Id").equals("tenant-a"), "missing tenant identity");
          require(headers.get("Idempotency-Key").equals("idem-1"), "missing idempotency");
          require(
              body.contains("\"resourcePolicy\":{\"mode\":\"AUTO\"}"),
              "missing AUTO resource policy");
          require(!body.contains("resourceClass"), "legacy resource class leaked");
          require(body.contains("\"mediaWorkload\":true"), "missing media flag");
          require(body.contains("\"requestedMediaStreams\":1"), "missing stream count");
          return new BrowserCloudClient.Response(200, "{\"sessionId\":\"ses_1234567890abcdef\"}");
        };
    var client =
        new BrowserCloudClient("https://browser.example", "tenant-a", null, "actor-a", transport);
    var result =
        client.createSession(
            new BrowserCloudClient.CreateSessionInput(
                "profile-a",
                "local",
                Map.of("mode", "AUTO"),
                1,
                10,
                List.of(),
                false,
                false,
                true,
                1,
                4000,
                Map.of(),
                "idem-1"));
    require(result.contains("ses_1234567890abcdef"), "session result not returned");
  }

  private static void structuredApiErrorIsPreserved() {
    BrowserCloudClient.Transport transport =
        (method, uri, headers, body) ->
            new BrowserCloudClient.Response(
                503,
                "{\"code\":\"MEDIA_QUOTA_REJECTED\",\"message\":\"rejected\",\"requestId\":\"req-1\"}");
    var client =
        new BrowserCloudClient("https://browser.example", "tenant-a", null, null, transport);
    try {
      client.startSession("ses_1234567890abcdef");
      throw new AssertionError("expected API exception");
    } catch (BrowserCloudClient.ApiException exception) {
      require(exception.status() == 503, "status not preserved");
      require(exception.code().equals("MEDIA_QUOTA_REJECTED"), "code not preserved");
      require(exception.requestId().equals("req-1"), "request ID not preserved");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
