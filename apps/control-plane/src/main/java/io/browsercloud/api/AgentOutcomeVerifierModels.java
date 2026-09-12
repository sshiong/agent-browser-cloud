package io.browsercloud.api;

import io.browsercloud.api.AgentExpectedOutcomeModels.ExpectedOutcomeDefinition;
import io.browsercloud.api.AgentExpectedOutcomeModels.ExpectedOutcomeEvaluation;
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

/** Fixed, read-only protocol shared with the isolated semantic Outcome Verifier Worker. */
public final class AgentOutcomeVerifierModels {

  private AgentOutcomeVerifierModels() {}

  public enum OutcomeDecision {
    VERIFIED,
    NOT_VERIFIED
  }

  public record ClaimAgentOutcomeJobRequest(
      @NotBlank @Pattern(regexp = "^outcome-verifier-worker/v1$") String protocolVersion,
      @NotNull @Size(min = 1, max = 16)
          Map<@Pattern(regexp = "^[a-z][A-Za-z0-9-]{1,63}$") String, Boolean> capabilities,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,128}$") String deploymentId,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:/-]{1,200}$") String modelRevision) {}

  public record AgentOutcomeJobClaimRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken) {}

  public record CompleteAgentOutcomeJobRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken,
      @NotNull OutcomeDecision decision,
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

  public record FailAgentOutcomeJobRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken,
      @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,127}$") String failureCode,
      boolean retryable) {}

  public record OutcomeExecutionEvidence(
      int stepOrdinal,
      String stepId,
      ToolId toolId,
      String status,
      String resultHash,
      String verification) {}

  public record OutcomeTargetEvidence(
      String role,
      String name,
      String controlType,
      boolean visible,
      boolean enabled,
      Boolean checked,
      Boolean selected) {}

  public record OutcomeStateEvidence(
      long stateVersion,
      long targetRevision,
      String stateHash,
      String url,
      String title,
      String stateQuality,
      String documentReadyState,
      long networkQuietMillis,
      boolean networkEvidenceFresh,
      Instant observedAt,
      List<OutcomeTargetEvidence> targets) {}

  /**
   * Contains no capability, secret, element identifier, raw page body, screenshot or action output.
   */
  public record AgentOutcomePayload(
      String taskId,
      String goal,
      RiskClass riskClass,
      List<String> allowedDomains,
      List<ExpectedOutcomeDefinition> expectedOutcomes,
      List<ExpectedOutcomeEvaluation> expectedOutcomeEvaluations,
      List<OutcomeExecutionEvidence> executionEvidence,
      OutcomeStateEvidence finalState,
      String evidenceHash,
      String dataPolicy) {}

  public record OutcomeModelDeploymentView(
      String deploymentId,
      String providerType,
      String modelName,
      String modelRevision,
      String dataPolicy,
      int maximumOutputTokens) {}

  public record AgentOutcomeJobView(
      String jobId,
      String verificationId,
      String taskId,
      String protocolVersion,
      String state,
      int attempt,
      int maximumAttempts,
      String workerId,
      long claimEpoch,
      Instant leaseExpiresAt,
      Instant availableAt,
      OutcomeModelDeploymentView deployment,
      OutcomeDecision decision,
      List<String> reasonCodes,
      BigDecimal confidence,
      String evidenceHash,
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

  public record AgentOutcomeJobClaimView(
      String claimToken,
      AgentOutcomeJobView job,
      AgentOutcomePayload outcomePayload,
      Instant leaseExpiresAt,
      long claimEpoch) {}

  public record AgentOutcomeVerificationView(
      String verificationId,
      String status,
      OutcomeDecision decision,
      List<String> reasonCodes,
      List<ExpectedOutcomeEvaluation> expectedOutcomeEvaluations,
      String evidenceHash,
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
