package io.browsercloud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ChallengeModels {

  private ChallengeModels() {}

  public record ChallengeRegion(double x, double y, double width, double height) {}

  public record ChallengeEventView(
      String challengeEventId,
      String sessionId,
      long contextEpoch,
      long stateVersion,
      long targetRevision,
      double confidence,
      Map<String, Object> evidence,
      String suspectedType,
      String accessOutcome,
      String targetRef,
      String targetSummary,
      String status,
      boolean oneClickEligible,
      Instant detectedAt,
      Instant authorizationDeadline,
      Instant expiresAt,
      Instant updatedAt) {}

  public record ChallengeEventListResponse(List<ChallengeEventView> items) {}

  public record ChallengePreviewView(
      ChallengeEventView challenge,
      String previewHash,
      ChallengeRegion highlight,
      boolean fresh,
      boolean canAuthorize,
      String blockingReason,
      Instant previewedAt) {}

  public record AuthorizeHumanAssistRequest(
      @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String previewHash,
      @Positive long expectedStateVersion,
      @Positive long expectedTargetRevision) {}

  public record HumanAssistView(
      String intentId,
      String challengeEventId,
      String sessionId,
      String userId,
      long contextEpoch,
      long stateVersion,
      long targetRevision,
      String allowedTargetRef,
      int allowedActionCount,
      int consumedCount,
      String authorizationEventId,
      String operationId,
      String requestId,
      String state,
      Instant expiresAt,
      Instant createdAt,
      Instant consumedAt,
      Instant completedAt,
      String errorCode) {}

  /** References a write-only, one-time OTP envelope; plaintext is never returned by this API. */
  public record SubmitChallengeInputResponseRequest(
      @NotBlank @Pattern(regexp = "^ais_[A-Za-z0-9]{20,32}$") String secretId) {}

  public record ChallengeInputResponseView(
      String intentId,
      String challengeEventId,
      String sessionId,
      String taskId,
      String purpose,
      String state,
      int maximumAttempts,
      String operationId,
      Instant expiresAt,
      Instant createdAt,
      Instant completedAt,
      String errorCode) {}
}
