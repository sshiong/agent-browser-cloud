package io.browsercloud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Write-only, purpose-bound values used by autonomous Agent TYPE_TEXT actions. */
public final class AgentInputSecretModels {

  private AgentInputSecretModels() {}

  public enum AgentInputSecretPurpose {
    USERNAME,
    PASSWORD,
    OTP
  }

  public record CreateAgentInputSecretRequest(
      @NotNull AgentInputSecretPurpose purpose,
      @NotBlank @Size(max = 2_000) String value,
      Instant expiresAt) {}

  public record AgentInputSecretView(
      String secretId,
      String sessionId,
      AgentInputSecretPurpose purpose,
      Instant expiresAt,
      boolean consumed) {}
}
