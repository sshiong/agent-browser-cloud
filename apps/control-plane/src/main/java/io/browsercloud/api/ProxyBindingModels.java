package io.browsercloud.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
      long probeSampleCount,
      Double probeSuccessRatePercent,
      Double latencyEwmaMs,
      Integer qualityScore,
      BigDecimal costPerGibUsd,
      int reputationScore,
      int maxConcurrentSessions,
      boolean automaticRoutingReady,
      Instant healthFreshUntil,
      int consecutiveFailures,
      long version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  public record ProxyBindingListResponse(List<ProxyBindingView> items, int total) {}

  public record ProxyRoutingCandidateScore(
      String bindingProfileId,
      String providerId,
      double routingScore,
      int qualityScore,
      int reputationScore,
      BigDecimal costPerGibUsd,
      double costScore,
      double regionScore,
      double headroomScore,
      int activeReservations,
      int maxConcurrentSessions) {}

  public record ProxyRoutingDecision(
      String sessionId,
      String bindingProfileId,
      String providerId,
      String selectionMode,
      Double routingScore,
      Integer qualityScore,
      Integer reputationScore,
      BigDecimal costPerGibUsd,
      Integer activeReservations,
      Integer maxConcurrentSessions,
      int candidateCount,
      List<ProxyRoutingCandidateScore> candidateScores,
      Instant selectedAt) {}

  public record ProxyRebindRequest(
      @NotBlank @Pattern(regexp = "^pbind_[a-zA-Z0-9]{16,32}$") String targetBindingProfileId,
      @NotBlank @Size(max = 240) String reason) {}

  public record ProxyRebindOperationResponse(
      String workflowId, String operationId, String phase, Instant createdAt) {}

  public record ProxyRebindView(
      String workflowId,
      String sessionId,
      String sourceBindingProfileId,
      String targetBindingProfileId,
      long targetBindingVersion,
      String hibernateOperationId,
      String restoreOperationId,
      String resyncRequestId,
      String phase,
      String recoveryResult,
      String failureReason,
      String requestedBy,
      String reason,
      String requestId,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt) {}
}
