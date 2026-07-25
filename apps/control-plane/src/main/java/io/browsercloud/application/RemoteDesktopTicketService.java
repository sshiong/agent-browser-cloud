package io.browsercloud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.RemoteDesktopConnectionResponse;
import io.browsercloud.domain.operation.ExclusiveOperation;
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
  private final Clock clock;

  @Autowired
  public RemoteDesktopTicketService(
      ObjectMapper objectMapper,
      @Value("${remote-desktop.ticket-secret:" + LOCAL_SECRET + "}") String secret,
      @Value("${remote-desktop.ticket-ttl-seconds:45}") long ttlSeconds,
      @Value("${app.environment:local}") String environment) {
    this(objectMapper, secret, ttlSeconds, environment, Clock.systemUTC());
  }

  RemoteDesktopTicketService(
      ObjectMapper objectMapper, String secret, long ttlSeconds, String environment, Clock clock) {
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
    this.objectMapper = objectMapper;
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.ttlSeconds = ttlSeconds;
    this.clock = clock;
  }

  public RemoteDesktopConnectionResponse issue(
      String tenantId, String sessionId, String actorId, ExclusiveOperation operation) {
    var expiresAt = Instant.now(clock).plusSeconds(ttlSeconds);
    var claims = new LinkedHashMap<String, Object>();
    claims.put("tenantId", tenantId);
    claims.put("sessionId", sessionId);
    claims.put("actorId", actorId);
    claims.put("coordinatorTerm", operation.coordinatorTerm());
    claims.put("contextEpoch", operation.contextEpoch());
    claims.put("operationEpoch", operation.operationEpoch());
    claims.put("expiresAtEpochSeconds", expiresAt.getEpochSecond());
    claims.put("nonce", UUID.randomUUID().toString().replace("-", ""));
    try {
      var payload = BASE64_URL.encodeToString(objectMapper.writeValueAsBytes(claims));
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      var signature =
          BASE64_URL.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
      return new RemoteDesktopConnectionResponse(
          "/desktop/v1/sessions/" + sessionId + "?ticket=" + payload + "." + signature,
          expiresAt,
          "rfb",
          operation.operationEpoch(),
          false);
    } catch (JsonProcessingException | GeneralSecurityException exception) {
      throw new IllegalStateException("remote desktop ticket signing failed", exception);
    }
  }
}
