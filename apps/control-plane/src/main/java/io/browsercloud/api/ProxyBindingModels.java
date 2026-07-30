package io.browsercloud.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ProxyBindingModels {
  private ProxyBindingModels() {}

  public record ProxyBindingRequest(
      @NotBlank @Size(max = 96) String name,
      @Size(max = 512) String description,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String providerId,
      @Pattern(regexp = "^[a-z0-9-]{1,32}$") String region,
      @NotBlank @Size(max = 128) String expectedExitIp,
      @Size(max = 512) @Pattern(regexp = "^(vault|secret|aws-sm|gcp-sm|azure-kv)://[^\\s]+$")
          String credentialRef,
      @NotNull Boolean enabled,
      @PositiveOrZero Long expectedVersion) {

    @AssertTrue(message = "expectedExitIp contains invalid characters")
    public boolean hasSafeExpectedExitIp() {
      return expectedExitIp == null
          || expectedExitIp
              .chars()
              .allMatch(
                  character ->
                      Character.digit(character, 16) >= 0 || character == '.' || character == ':');
    }
  }

  public record ProxyBindingView(
      String bindingProfileId,
      String name,
      String description,
      String providerId,
      String region,
      String expectedExitIp,
      boolean credentialConfigured,
      boolean enabled,
      String healthState,
      String lastVerifiedExitIp,
      Instant lastHealthCheckedAt,
      String lastFailureReason,
      long version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  public record ProxyBindingListResponse(List<ProxyBindingView> items, int total) {}
}
