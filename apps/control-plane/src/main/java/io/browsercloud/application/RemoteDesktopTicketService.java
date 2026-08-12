package io.browsercloud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.RemoteDesktopConnectionResponse;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.session.SessionContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 为 Browser Node 数据面签发短期 HMAC 连接票据；票据不进入 URL 之外的日志。 */
@Service
public class RemoteDesktopTicketService {

  static final String LOCAL_SECRET = "browsercloud-local-remote-desktop-ticket-secret-v1";
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

  private final ObjectMapper objectMapper;
  private final byte[] secret;
  private final long ttlSeconds;
  private final int controlActorBitrateLimitKbps;
  private final int controlActorFrameRateLimitFps;
  private final int viewerActorBitrateLimitKbps;
  private final int viewerActorFrameRateLimitFps;
  private final Clock clock;

  @Autowired
  public RemoteDesktopTicketService(
      ObjectMapper objectMapper,
      @Value("${remote-desktop.ticket-secret:" + LOCAL_SECRET + "}") String secret,
      @Value("${remote-desktop.ticket-ttl-seconds:45}") long ttlSeconds,
      @Value("${remote-desktop.control-actor-bitrate-limit-kbps:8000}")
          int controlActorBitrateLimitKbps,
      @Value("${remote-desktop.control-actor-frame-rate-limit-fps:30}")
          int controlActorFrameRateLimitFps,
      @Value("${remote-desktop.viewer-actor-bitrate-limit-kbps:4000}")
          int viewerActorBitrateLimitKbps,
      @Value("${remote-desktop.viewer-actor-frame-rate-limit-fps:15}")
          int viewerActorFrameRateLimitFps,
      @Value("${app.environment:local}") String environment) {
    this(
        objectMapper,
        secret,
        ttlSeconds,
        controlActorBitrateLimitKbps,
        controlActorFrameRateLimitFps,
        viewerActorBitrateLimitKbps,
        viewerActorFrameRateLimitFps,
        environment,
        Clock.systemUTC());
  }

  RemoteDesktopTicketService(
      ObjectMapper objectMapper, String secret, long ttlSeconds, String environment, Clock clock) {
    this(objectMapper, secret, ttlSeconds, 8_000, 30, 4_000, 15, environment, clock);
  }

  RemoteDesktopTicketService(
      ObjectMapper objectMapper,
      String secret,
      long ttlSeconds,
      int controlActorBitrateLimitKbps,
      int controlActorFrameRateLimitFps,
      int viewerActorBitrateLimitKbps,
      int viewerActorFrameRateLimitFps,
      String environment,
      Clock clock) {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalArgumentException(
          "remote desktop ticket secret must contain at least 32 bytes");
    }
    if ("production".equalsIgnoreCase(environment) && LOCAL_SECRET.equals(secret)) {
      throw new IllegalArgumentException(
          "REMOTE_DESKTOP_TICKET_SECRET must be overridden in production");
    }
    if (ttlSeconds < 10 || ttlSeconds > 120) {
      throw new IllegalArgumentException("remote desktop ticket TTL must be between 10 and 120");
    }
    validateActorQuota("control", controlActorBitrateLimitKbps, controlActorFrameRateLimitFps);
    validateActorQuota("viewer", viewerActorBitrateLimitKbps, viewerActorFrameRateLimitFps);
    this.objectMapper = objectMapper;
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.ttlSeconds = ttlSeconds;
    this.controlActorBitrateLimitKbps = controlActorBitrateLimitKbps;
    this.controlActorFrameRateLimitFps = controlActorFrameRateLimitFps;
    this.viewerActorBitrateLimitKbps = viewerActorBitrateLimitKbps;
    this.viewerActorFrameRateLimitFps = viewerActorFrameRateLimitFps;
    this.clock = clock;
  }

  private static void validateActorQuota(String kind, int bitrateLimitKbps, int frameRateLimitFps) {
    if (bitrateLimitKbps < 250 || bitrateLimitKbps > 100_000) {
      throw new IllegalArgumentException(
          kind + " remote desktop actor bitrate must be between 250 and 100000 Kbps");
    }
    if (frameRateLimitFps < 1 || frameRateLimitFps > 60) {
      throw new IllegalArgumentException(
          kind + " remote desktop actor frame rate must be between 1 and 60 FPS");
    }
  }

  /**
   * Issues a Session-bound collaborative desktop ticket.
   *
   * <p>The legacy response field is still named {@code operationEpoch} for wire compatibility, but
   * collaborative tickets bind it to the current Context Epoch. Opening the observer therefore does
   * not create, replace, or abort an exclusive Agent/Human operation.
   */
  public RemoteDesktopConnectionResponse issueCollaborative(
      String tenantId, String sessionId, String actorId, SessionContext session) {
    return issueCollaborative(tenantId, sessionId, actorId, session, false);
  }

  public RemoteDesktopConnectionResponse issueCollaborative(
      String tenantId, String sessionId, String actorId, SessionContext session, boolean viewOnly) {
    var bitrate = viewOnly ? viewerActorBitrateLimitKbps : controlActorBitrateLimitKbps;
    var frameRate = viewOnly ? viewerActorFrameRateLimitFps : controlActorFrameRateLimitFps;
    return issueCollaborative(tenantId, sessionId, actorId, session, viewOnly, bitrate, frameRate);
  }

  public RemoteDesktopConnectionResponse issueCollaborative(
      String tenantId,
      String sessionId,
      String actorId,
      SessionContext session,
      boolean viewOnly,
      int actorBitrateLimitKbps,
      int actorFrameRateLimitFps) {
    return issue(
        tenantId,
        sessionId,
        actorId,
        session.coordinatorTerm(),
        session.contextEpoch(),
        session.contextEpoch(),
        "COLLABORATIVE",
        viewOnly,
        actorBitrateLimitKbps,
        actorFrameRateLimitFps);
  }

  /** Issues a ticket for an already established explicit HumanTakeover barrier. */
  public RemoteDesktopConnectionResponse issueExclusive(
      String tenantId, String sessionId, String actorId, ExclusiveOperation operation) {
    return issueExclusive(
        tenantId,
        sessionId,
        actorId,
        operation,
        controlActorBitrateLimitKbps,
        controlActorFrameRateLimitFps);
  }

  public RemoteDesktopConnectionResponse issueExclusive(
      String tenantId,
      String sessionId,
      String actorId,
      ExclusiveOperation operation,
      int actorBitrateLimitKbps,
      int actorFrameRateLimitFps) {
    return issue(
        tenantId,
        sessionId,
        actorId,
        operation.coordinatorTerm(),
        operation.contextEpoch(),
        operation.operationEpoch(),
        "EXCLUSIVE_TAKEOVER",
        false,
        actorBitrateLimitKbps,
        actorFrameRateLimitFps);
  }

  private RemoteDesktopConnectionResponse issue(
      String tenantId,
      String sessionId,
      String actorId,
      long coordinatorTerm,
      long contextEpoch,
      long bindingEpoch,
      String accessMode,
      boolean viewOnly,
      int actorBitrateLimitKbps,
      int actorFrameRateLimitFps) {
    validateActorQuota("issued", actorBitrateLimitKbps, actorFrameRateLimitFps);
    var expiresAt = Instant.now(clock).plusSeconds(ttlSeconds);
    var connectionId = "rdc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    var claims = new LinkedHashMap<String, Object>();
    claims.put("tenantId", tenantId);
    claims.put("sessionId", sessionId);
    claims.put("actorId", actorId);
    claims.put("connectionId", connectionId);
    claims.put("coordinatorTerm", coordinatorTerm);
    claims.put("contextEpoch", contextEpoch);
    claims.put("operationEpoch", bindingEpoch);
    claims.put("accessMode", accessMode);
    claims.put("viewOnly", viewOnly);
    claims.put("actorBitrateLimitKbps", actorBitrateLimitKbps);
    claims.put("actorFrameRateLimitFps", actorFrameRateLimitFps);
    claims.put("expiresAtEpochSeconds", expiresAt.getEpochSecond());
    claims.put("nonce", UUID.randomUUID().toString().replace("-", ""));
    try {
      var payload = BASE64_URL.encodeToString(objectMapper.writeValueAsBytes(claims));
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      var signature =
          BASE64_URL.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
      return new RemoteDesktopConnectionResponse(
          connectionId,
          "/desktop/v1/sessions/" + sessionId + "?ticket=" + payload + "." + signature,
          expiresAt,
          "rfb",
          bindingEpoch,
          viewOnly,
          actorBitrateLimitKbps,
          actorFrameRateLimitFps);
    } catch (JsonProcessingException | GeneralSecurityException exception) {
      throw new IllegalStateException("remote desktop ticket signing failed", exception);
    }
  }
}
