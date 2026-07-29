package io.browsercloud.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class BrowserCloudClient {

  public interface Transport {
    Response send(String method, URI uri, Map<String, String> headers, String body)
        throws IOException, InterruptedException;
  }

  public record Response(int status, String body) {}

  public record CreateSessionInput(
      String profileId,
      String region,
      Map<String, Object> resourcePolicy,
      int requestedTabs,
      int agentActionsPerMinute,
      List<String> extensionIds,
      boolean remoteDesktop,
      boolean web3Workload,
      boolean mediaWorkload,
      int requestedMediaStreams,
      int mediaBitrateKbps,
      boolean videoRecording,
      Map<String, String> metadata,
      String idempotencyKey) {
    public CreateSessionInput(
        String profileId,
        String region,
        Map<String, Object> resourcePolicy,
        int requestedTabs,
        int agentActionsPerMinute,
        List<String> extensionIds,
        boolean remoteDesktop,
        boolean web3Workload,
        boolean mediaWorkload,
        int requestedMediaStreams,
        int mediaBitrateKbps,
        Map<String, String> metadata,
        String idempotencyKey) {
      this(
          profileId,
          region,
          resourcePolicy,
          requestedTabs,
          agentActionsPerMinute,
          extensionIds,
          remoteDesktop,
          web3Workload,
          mediaWorkload,
          requestedMediaStreams,
          mediaBitrateKbps,
          false,
          metadata,
          idempotencyKey);
    }
  }

  public static final class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final String requestId;

    ApiException(int status, String code, String message, String requestId) {
      super(message);
      this.status = status;
      this.code = code;
      this.requestId = requestId;
    }

    public int status() {
      return status;
    }

    public String code() {
      return code;
    }

    public String requestId() {
      return requestId;
    }
  }

  private static final Pattern ABSOLUTE_HTTP = Pattern.compile("^https?://.+");
  private final URI baseUri;
  private final String tenantId;
  private final String accessToken;
  private final String actorId;
  private final Transport transport;

  public BrowserCloudClient(
      String baseUrl, String tenantId, String accessToken, String actorId, Transport transport) {
    if (baseUrl == null || !ABSOLUTE_HTTP.matcher(baseUrl).matches()) {
      throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URL");
    }
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenantId is required");
    }
    this.baseUri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/v1");
    this.tenantId = tenantId;
    this.accessToken = accessToken;
    this.actorId = actorId;
    this.transport = transport == null ? httpTransport() : transport;
  }

  public String listSessions(int limit, int offset) {
    return request("GET", "/sessions?limit=" + limit + "&offset=" + offset, null, null);
  }

  public String createSession(CreateSessionInput input) {
    Objects.requireNonNull(input, "input");
    var body =
        "{"
            + field("tenantId", tenantId)
            + ","
            + field("profileId", input.profileId())
            + ","
            + field("region", input.region())
            + ","
            + objectMap(
                "resourcePolicy",
                input.resourcePolicy() == null
                    ? Map.of("mode", "AUTO")
                    : input.resourcePolicy())
            + ",\"requestedTabs\":"
            + (input.requestedTabs() == 0 ? 1 : input.requestedTabs())
            + ",\"agentActionsPerMinute\":"
            + input.agentActionsPerMinute()
            + ",\"extensionIds\":"
            + stringList(input.extensionIds())
            + ",\"remoteDesktop\":"
            + input.remoteDesktop()
            + ",\"web3Workload\":"
            + input.web3Workload()
            + ",\"mediaWorkload\":"
            + input.mediaWorkload()
            + ",\"requestedMediaStreams\":"
            + input.requestedMediaStreams()
            + ",\"mediaBitrateKbps\":"
            + input.mediaBitrateKbps()
            + ",\"videoRecording\":"
            + input.videoRecording()
            + ",\"metadata\":"
            + stringMap(input.metadata())
            + "}";
    return request(
        "POST",
        "/sessions",
        body,
        input.idempotencyKey() == null ? UUID.randomUUID().toString() : input.idempotencyKey());
  }

  public String startSession(String sessionId) {
    return request("POST", "/sessions/" + sessionId + ":start", null, null);
  }

  public String terminateSession(String sessionId) {
    return request("POST", "/sessions/" + sessionId + ":terminate", null, null);
  }

  private String request(String method, String path, String body, String idempotencyKey) {
    var headers = new java.util.LinkedHashMap<String, String>();
    headers.put("Accept", "application/json");
    headers.put("Content-Type", "application/json");
    if (accessToken != null && !accessToken.isBlank()) {
      headers.put("Authorization", "Bearer " + accessToken);
    } else {
      headers.put("X-Tenant-Id", tenantId);
      if (actorId != null && !actorId.isBlank()) {
        headers.put("X-Actor-Id", actorId);
      }
    }
    if (idempotencyKey != null) {
      headers.put("Idempotency-Key", idempotencyKey);
    }
    try {
      var response = transport.send(method, baseUri.resolve(baseUri.getPath() + path), headers, body);
      if (response.status() < 200 || response.status() >= 300) {
        throw new ApiException(
            response.status(),
            jsonString(response.body(), "code", "UNKNOWN_ERROR"),
            jsonString(response.body(), "message", "HTTP " + response.status()),
            jsonString(response.body(), "requestId", null));
      }
      return response.body();
    } catch (IOException exception) {
      throw new IllegalStateException("Browser Cloud request failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Browser Cloud request interrupted", exception);
    }
  }

  private static Transport httpTransport() {
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    return (method, uri, headers, body) -> {
      var builder =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(30))
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body));
      headers.forEach(builder::header);
      var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    };
  }

  private static String field(String name, String value) {
    return "\"" + escape(name) + "\":\"" + escape(value) + "\"";
  }

  private static String stringList(List<String> values) {
    if (values == null) {
      return "[]";
    }
    return values.stream().map(value -> "\"" + escape(value) + "\"").collect(
        java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private static String stringMap(Map<String, String> values) {
    if (values == null) {
      return "{}";
    }
    return values.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> field(entry.getKey(), entry.getValue()))
        .collect(java.util.stream.Collectors.joining(",", "{", "}"));
  }

  private static String objectMap(String name, Map<String, Object> values) {
    var body =
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> "\"" + escape(entry.getKey()) + "\":" + jsonValue(entry.getValue()))
            .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    return "\"" + escape(name) + "\":" + body;
  }

  private static String jsonValue(Object value) {
    if (value instanceof String text) {
      return "\"" + escape(text) + "\"";
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    throw new IllegalArgumentException("resource policy values must be strings, numbers or booleans");
  }

  private static String escape(String value) {
    return Objects.requireNonNull(value, "JSON string")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  private static String jsonString(String json, String key, String fallback) {
    var matcher =
        Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
            .matcher(json == null ? "" : json);
    return matcher.find() ? matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : fallback;
  }
}
