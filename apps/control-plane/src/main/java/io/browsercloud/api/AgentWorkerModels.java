package io.browsercloud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

/** Data-minimized fixed protocol shared with the isolated Agent Worker. */
public final class AgentWorkerModels {

  private AgentWorkerModels() {}

  public record ClaimAgentExecutionJobRequest(
      @NotBlank @Pattern(regexp = "^agent-worker/v1$") String protocolVersion,
      @NotNull @Size(min = 1, max = 16)
          Map<@Pattern(regexp = "^[a-z][A-Za-z0-9-]{1,63}$") String, Boolean> capabilities) {}

  public record AgentExecutionJobClaimRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken) {}

  public record FailAgentExecutionJobRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken,
      @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,127}$") String failureCode,
      boolean retryable) {}

  public record AgentExecutionJobView(
      String jobId,
      String taskId,
      String protocolVersion,
      String state,
      int attempt,
      int maximumAttempts,
      String workerId,
      long claimEpoch,
      Instant leaseExpiresAt,
      Instant availableAt,
      Instant startedAt,
      Instant completedAt,
      String failureCode,
      Instant updatedAt) {}

  public record AgentExecutionJobClaimView(
      String claimToken, AgentExecutionJobView job, Instant leaseExpiresAt, long claimEpoch) {}
}
