package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** HMAC 签名的短期 Tool Capability Token；Tool Service 必须重新验证绑定字段。 */
@Service
public final class AgentCapabilityTokenService {

  private final ObjectMapper objectMapper;
  private final byte[] secret;

  public AgentCapabilityTokenService(
      ObjectMapper objectMapper,
      @Value("${agent.capability-token-secret:browsercloud-local-agent-capability-token-secret-v1}")
          String secret,
      @Value("${app.environment:local}") String environment) {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("Agent capability token secret must be at least 32 bytes");
    }
    if (environment.equalsIgnoreCase("production")
        && secret.equals("browsercloud-local-agent-capability-token-secret-v1")) {
      throw new IllegalStateException(
          "AGENT_CAPABILITY_TOKEN_SECRET must be configured in production");
    }
    this.objectMapper = objectMapper;
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  public IssuedCapability issue(
      String tenantId,
      String sessionId,
      String intentId,
      String operationId,
      ToolId toolId,
      String allowedDomain,
      String dataScope,
      RiskClass riskClass,
      Instant expiresAt) {
    var claims =
        new CapabilityClaims(
            "cap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
            tenantId,
            sessionId,
            intentId,
            operationId,
            toolId,
            toolId.name(),
            allowedDomain == null ? "" : allowedDomain,
            dataScope,
            riskClass,
            expiresAt,
            1);
    try {
      var payload =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(objectMapper.writeValueAsBytes(claims));
      var signature =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(sign(payload.getBytes(StandardCharsets.UTF_8)));
      return new IssuedCapability(claims.tokenId(), payload + "." + signature, expiresAt);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize capability token", exception);
    }
  }

  public CapabilityClaims verify(
      String token,
      String tenantId,
      String sessionId,
      String intentId,
      String operationId,
      ToolId toolId,
      String targetDomain,
      String dataScope,
      Instant now) {
    var claims = verifiedClaims(token);
    var domain = targetDomain == null ? "" : targetDomain;
    requireBindings(claims, tenantId, sessionId, intentId, operationId, toolId, dataScope, now);
    if (!claims.allowedDomain().equals(domain)) {
      throw new InvalidCapabilityTokenException();
    }
    return claims;
  }

  /**
   * Verifies a read capability after an allowed cross-domain browser transition.
   *
   * <p>The capability remains bound to the immutable Task/Intent and to one domain that was
   * authorized when the plan was created. The current page and that original binding must both
   * remain inside the persisted Task allowlist. This preserves old tokens while allowing an
   * OPEN_TAB or an ordinary allowlisted navigation to finish on another authorized domain.
   */
  public CapabilityClaims verifyWithinAllowedDomains(
      String token,
      String tenantId,
      String sessionId,
      String intentId,
      String operationId,
      ToolId toolId,
      String targetDomain,
      Set<String> allowedDomains,
      String dataScope,
      Instant now) {
    var claims = verifiedClaims(token);
    var domain = targetDomain == null ? "" : targetDomain;
    requireBindings(claims, tenantId, sessionId, intentId, operationId, toolId, dataScope, now);
    if (domain.isBlank()
        || claims.allowedDomain().isBlank()
        || !allowedDomains.contains(domain)
        || !allowedDomains.contains(claims.allowedDomain())) {
      throw new InvalidCapabilityTokenException();
    }
    return claims;
  }

  private CapabilityClaims verifiedClaims(String token) {
    try {
      var parts = token.split("\\.", -1);
      if (parts.length != 2
          || !MessageDigest.isEqual(
              sign(parts[0].getBytes(StandardCharsets.UTF_8)),
              Base64.getUrlDecoder().decode(parts[1]))) {
        throw new InvalidCapabilityTokenException();
      }
      var claims =
          objectMapper.readValue(Base64.getUrlDecoder().decode(parts[0]), CapabilityClaims.class);
      return claims;
    } catch (IllegalArgumentException | IOException exception) {
      throw new InvalidCapabilityTokenException();
    }
  }

  private static void requireBindings(
      CapabilityClaims claims,
      String tenantId,
      String sessionId,
      String intentId,
      String operationId,
      ToolId toolId,
      String dataScope,
      Instant now) {
    if (!claims.tenantId().equals(tenantId)
        || !claims.sessionId().equals(sessionId)
        || !claims.intentId().equals(intentId)
        || !claims.operationId().equals(operationId)
        || claims.toolId() != toolId
        || !claims.allowedAction().equals(toolId.name())
        || !claims.dataScope().equals(dataScope)
        || claims.maxCalls() != 1
        || !claims.expiresAt().isAfter(now)) {
      throw new InvalidCapabilityTokenException();
    }
  }

  private byte[] sign(byte[] payload) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
    }
  }

  public record IssuedCapability(String tokenId, String token, Instant expiresAt) {}

  public record CapabilityClaims(
      String tokenId,
      String tenantId,
      String sessionId,
      String intentId,
      String operationId,
      ToolId toolId,
      String allowedAction,
      String allowedDomain,
      String dataScope,
      RiskClass riskClass,
      Instant expiresAt,
      int maxCalls) {}

  public static final class InvalidCapabilityTokenException extends RuntimeException {
    public InvalidCapabilityTokenException() {
      super("Capability token is invalid");
    }
  }
}
