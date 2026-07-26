package io.browsercloud.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 对 TypeText 正文做 AES-256-GCM 信封加密；Plan/API 只暴露 Hash、长度与数据分类。 */
@Service
public final class AgentActionPayloadService {

  private static final String LOCAL_SECRET = "browsercloud-local-agent-action-payload-secret-v1";
  private static final int GCM_TAG_BITS = 128;
  private final SecretKeySpec key;
  private final SecureRandom secureRandom = new SecureRandom();

  public AgentActionPayloadService(
      @Value("${agent.action-payload-secret:" + LOCAL_SECRET + "}") String secret,
      @Value("${app.environment:local}") String environment) {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("Agent action payload secret must be at least 32 bytes");
    }
    if (environment.equalsIgnoreCase("production") && secret.equals(LOCAL_SECRET)) {
      throw new IllegalStateException(
          "AGENT_ACTION_PAYLOAD_SECRET must be configured in production");
    }
    try {
      this.key =
          new SecretKeySpec(
              MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)),
              "AES");
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public String seal(String tenantId, String taskId, String stepId, String plaintext) {
    try {
      var iv = new byte[12];
      secureRandom.nextBytes(iv);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
      cipher.updateAAD(aad(tenantId, taskId, stepId));
      var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return "v1."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
          + "."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Failed to seal Agent action payload", exception);
    }
  }

  public String unseal(String tenantId, String taskId, String stepId, String sealedPayload) {
    try {
      var parts = sealedPayload.split("\\.", -1);
      if (parts.length != 3 || !parts[0].equals("v1")) {
        throw new InvalidActionPayloadException();
      }
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          key,
          new GCMParameterSpec(GCM_TAG_BITS, Base64.getUrlDecoder().decode(parts[1])));
      cipher.updateAAD(aad(tenantId, taskId, stepId));
      return new String(
          cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new InvalidActionPayloadException();
    }
  }

  private static byte[] aad(String tenantId, String taskId, String stepId) {
    return (tenantId + "\n" + taskId + "\n" + stepId).getBytes(StandardCharsets.UTF_8);
  }

  public static final class InvalidActionPayloadException extends RuntimeException {
    public InvalidActionPayloadException() {
      super("Agent action payload is invalid");
    }
  }
}
