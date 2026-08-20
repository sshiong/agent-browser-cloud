package io.browsercloud.api;

import io.browsercloud.domain.agent.AgentModels.AgentControlMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public policy plus the fixed, least-privilege screenshot vision-worker protocol. */
public final class ChallengeAutomationModels {

  private ChallengeAutomationModels() {}

  public enum VisualDecision {
    ACT,
    ESCALATE
  }

  public enum VisualActionType {
    CLICK,
    SLIDE
  }

  public record ChallengeAutomationPolicyView(
      String sessionId,
      AgentControlMode controlMode,
      int sensitiveInputMaximumAttempts,
      boolean enabled,
      int maximumAttempts,
      BigDecimal minimumConfidence,
      boolean allowMultiClick,
      boolean allowSlide,
      int motionMinimumSteps,
      int motionMaximumSteps,
      int motionMinimumDelayMs,
      int motionMaximumDelayMs,
      BigDecimal targetOffsetRatio,
      Instant updatedAt) {}

  public record UpdateChallengeAutomationPolicyRequest(
      AgentControlMode controlMode,
      @Min(1) @Max(10) Integer sensitiveInputMaximumAttempts,
      boolean enabled,
      @Min(0) @Max(10) int maximumAttempts,
      @NotNull @DecimalMin("0.5") @DecimalMax("1.0") BigDecimal minimumConfidence,
      boolean allowMultiClick,
      boolean allowSlide,
      @Min(4) @Max(32) Integer motionMinimumSteps,
      @Min(4) @Max(40) Integer motionMaximumSteps,
      @Min(5) @Max(100) Integer motionMinimumDelayMs,
      @Min(5) @Max(150) Integer motionMaximumDelayMs,
      @NotNull @DecimalMin("0.0") @DecimalMax("0.35") BigDecimal targetOffsetRatio) {
    public UpdateChallengeAutomationPolicyRequest {
      motionMinimumSteps = motionMinimumSteps == null ? 8 : motionMinimumSteps;
      motionMaximumSteps = motionMaximumSteps == null ? 18 : motionMaximumSteps;
      motionMinimumDelayMs = motionMinimumDelayMs == null ? 12 : motionMinimumDelayMs;
      motionMaximumDelayMs = motionMaximumDelayMs == null ? 45 : motionMaximumDelayMs;
      targetOffsetRatio = targetOffsetRatio == null ? new BigDecimal("0.15") : targetOffsetRatio;
    }

    public UpdateChallengeAutomationPolicyRequest(
        AgentControlMode controlMode,
        Integer sensitiveInputMaximumAttempts,
        boolean enabled,
        int maximumAttempts,
        BigDecimal minimumConfidence,
        boolean allowMultiClick,
        boolean allowSlide) {
      this(
          controlMode,
          sensitiveInputMaximumAttempts,
          enabled,
          maximumAttempts,
          minimumConfidence,
          allowMultiClick,
          allowSlide,
          8,
          18,
          12,
          45,
          new BigDecimal("0.15"));
    }
  }

  public record ChallengeAutomationRunView(
      String runId,
      String challengeEventId,
      String state,
      int attemptCount,
      int maximumAttempts,
      String lastAction,
      String lastErrorCode,
      Instant updatedAt,
      Instant completedAt) {}

  public record ClaimChallengeVisualJobRequest(
      @NotBlank @Pattern(regexp = "^challenge-vision-worker/v1$") String protocolVersion,
      @NotNull @Size(min = 1, max = 16)
          Map<@Pattern(regexp = "^[a-z][A-Za-z0-9-]{1,63}$") String, Boolean> capabilities,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,128}$") String deploymentId,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:/-]{1,200}$") String modelRevision) {}

  public record ChallengeVisualJobClaimRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken) {}

  public record ChallengeVisualAction(
      @NotNull VisualActionType actionType,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal x,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal y,
      @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal endX,
      @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal endY,
      @Min(1) @Max(5) int repeatCount) {}

  public record CompleteChallengeVisualJobRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken,
      @NotNull VisualDecision decision,
      @NotNull @Size(max = 8) List<@Valid ChallengeVisualAction> actions,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,128}$") String deploymentId,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:/-]{1,200}$") String modelRevision,
      @Size(max = 256) @Pattern(regexp = "^[A-Za-z0-9._:/-]{1,256}$") String providerRequestId,
      @Min(0) @Max(1_000_000) int inputTokens,
      @Min(0) @Max(100_000) int outputTokens,
      @Min(0) @Max(600_000) int latencyMs,
      @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String outputHash) {}

  public record FailChallengeVisualJobRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken,
      @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,127}$") String failureCode,
      boolean retryable) {}

  public record ChallengeVisualJobView(
      String jobId,
      String runId,
      String challengeEventId,
      String state,
      int attemptNumber,
      int maximumAttempts,
      String workerId,
      long claimEpoch,
      Instant leaseExpiresAt,
      VisualDecision decision,
      List<ChallengeVisualAction> actions,
      BigDecimal confidence,
      String failureCode,
      Instant updatedAt) {}

  public record ChallengeVisualJobClaimView(
      String claimToken,
      ChallengeVisualJobView job,
      String screenshotUrl,
      Instant screenshotExpiresAt,
      String challengeType,
      String targetSummary,
      boolean allowMultiClick,
      boolean allowSlide,
      BigDecimal minimumConfidence) {}
}
