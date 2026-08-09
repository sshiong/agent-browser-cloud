package io.browsercloud.api;

import io.browsercloud.domain.agent.AgentModels.ExecutionStrategy;
import io.browsercloud.domain.agent.AgentModels.RiskClass;
import io.browsercloud.domain.agent.AgentModels.ToolId;
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

/** Fixed, capability-free protocol shared with the isolated Reviewer Worker. */
public final class AgentReviewerModels {

  private AgentReviewerModels() {}

  public enum ReviewerDecision {
    APPROVE,
    REJECT
  }

  public record ClaimAgentReviewJobRequest(
      @NotBlank @Pattern(regexp = "^reviewer-worker/v1$") String protocolVersion,
      @NotNull @Size(min = 1, max = 16)
          Map<@Pattern(regexp = "^[a-z][A-Za-z0-9-]{1,63}$") String, Boolean> capabilities,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,128}$") String deploymentId,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:/-]{1,200}$") String modelRevision) {}

  public record AgentReviewJobClaimRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken) {}

  public record CompleteAgentReviewJobRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken,
      @NotNull ReviewerDecision decision,
      @NotNull @Size(min = 1, max = 10)
          List<@Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$") String> reasonCodes,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,128}$") String deploymentId,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:/-]{1,200}$") String modelRevision,
      @Size(max = 256) @Pattern(regexp = "^[A-Za-z0-9._:/-]{1,256}$") String providerRequestId,
      @Min(0) @Max(1_000_000) int inputTokens,
      @Min(0) @Max(100_000) int outputTokens,
      @Min(0) @Max(600_000) int latencyMs,
      @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String outputHash) {}

  public record FailAgentReviewJobRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken,
      @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,127}$") String failureCode,
      boolean retryable) {}

  public record AgentReviewStepView(
      String stepId,
      ToolId toolId,
      RiskClass riskClass,
      String targetOrigin,
      String targetRefHash,
      String dataClass,
      Integer payloadLength,
      boolean requiredConfirmation,
      ExecutionStrategy strategy,
      String requiredStateQuality,
      String verification) {}

  /** Deliberately excludes capability tokens, sealed payloads, page state and context content. */
  public record AgentReviewPayload(
      String taskId,
      String goal,
      RiskClass riskClass,
      List<String> allowedDomains,
      int maximumActions,
      int replanBudget,
      List<AgentReviewStepView> steps,
      String planHash,
      String dataPolicy) {}

  public record ReviewerModelDeploymentView(
      String deploymentId,
      String providerType,
      String modelName,
      String modelRevision,
      String dataPolicy,
      int maximumOutputTokens) {}

  public record AgentReviewJobView(
      String jobId,
      String reviewId,
      String taskId,
      String protocolVersion,
      String state,
      int attempt,
      int maximumAttempts,
      String workerId,
      long claimEpoch,
      Instant leaseExpiresAt,
      Instant availableAt,
      ReviewerModelDeploymentView deployment,
      ReviewerDecision decision,
      List<String> reasonCodes,
      BigDecimal confidence,
      String inputHash,
      String outputHash,
      String providerRequestId,
      Integer inputTokens,
      Integer outputTokens,
      Long costMicros,
      Integer latencyMs,
      Instant startedAt,
      Instant completedAt,
      String failureCode,
      Instant updatedAt) {}

  public record AgentReviewJobClaimView(
      String claimToken,
      AgentReviewJobView job,
      AgentReviewPayload reviewPayload,
      Instant leaseExpiresAt,
      long claimEpoch) {}

  public record AgentReviewView(
      String reviewId,
      String status,
      ReviewerDecision decision,
      List<String> reasonCodes,
      String planHash,
      String deploymentId,
      String modelName,
      String modelRevision,
      Integer inputTokens,
      Integer outputTokens,
      Long costMicros,
      Integer latencyMs,
      String failureCode,
      Instant completedAt) {}
}
