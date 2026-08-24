package io.browsercloud.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Coarse, bounded browser.evaluate contract. Script source is never returned by the API. */
public final class AgentBrowserEvaluationModels {
  private AgentBrowserEvaluationModels() {}

  public enum EvaluationMode {
    READ_ONLY,
    PAGE_ACTION
  }

  public record CreateEvaluationRequest(
      @NotBlank @Size(max = 2_000) String goal,
      @NotNull EvaluationMode mode,
      @NotBlank @Size(max = 16_384) String expression,
      @NotBlank @Pattern(regexp = "^[0-9]+:[0-9]+:[a-f0-9]{64}$") String expectedStateCursor,
      Boolean awaitPromise,
      @Min(100) @Max(5_000) Integer timeoutMs,
      @Min(1) @Max(32_768) Integer maximumResultBytes) {}

  public record EvaluationView(
      String evaluationId,
      String sessionId,
      EvaluationMode mode,
      String state,
      String expectedStateCursor,
      String stateCursorAfter,
      String activeTabId,
      String activeTabIdAfter,
      String expressionSha256,
      int expressionBytes,
      boolean awaitPromise,
      int timeoutMs,
      int maximumResultBytes,
      String resultType,
      JsonNode result,
      Integer resultBytes,
      Integer redactedValueCount,
      String exceptionClass,
      String exceptionMessage,
      String errorCode,
      Integer durationMs,
      String requestId,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt) {}
}
