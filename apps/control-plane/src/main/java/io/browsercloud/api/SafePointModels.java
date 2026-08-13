package io.browsercloud.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;

public final class SafePointModels {

  private SafePointModels() {}

  public record NodeSafetyObservation(
      Boolean inputActive,
      Boolean activeDrag,
      Integer pressedKeyCount,
      Integer pressedButtonCount,
      Integer activeUploadCount,
      Integer activeDownloadCount,
      Integer activeFormSubmissionCount,
      Integer activeSpaMutationCount,
      Integer activePaymentOrSecurityCount,
      Integer activeCriticalTransactionCount,
      Instant observedAt) {

    public boolean hasInputObservation() {
      return inputActive != null
          || activeDrag != null
          || pressedKeyCount != null
          || pressedButtonCount != null;
    }

    public boolean hasBrowserActivityObservation() {
      return activeUploadCount != null
          || activeDownloadCount != null
          || activeFormSubmissionCount != null;
    }

    public boolean hasBrowserTransactionObservation() {
      return activeSpaMutationCount != null
          || activePaymentOrSecurityCount != null
          || activeCriticalTransactionCount != null;
    }
  }

  public record SafePointBlockerView(
      String code, String source, String detail, Instant observedAt, Instant expiresAt) {}

  public record SessionSafePointView(
      String sessionId,
      boolean safe,
      String state,
      String dataFreshness,
      String nodeId,
      long contextEpoch,
      Instant evaluatedAt,
      Instant lastNodeObservationAt,
      List<SafePointBlockerView> blockers) {}

  public enum ApplicationSafetySignalType {
    FILE_TRANSFER,
    FORM_SUBMISSION,
    PAYMENT_OR_SECURITY,
    CRITICAL_TRANSACTION,
    BUSINESS_RECOVERY_UNKNOWN
  }

  public record CreateSafetyLeaseRequest(
      @NotNull ApplicationSafetySignalType signalType,
      @NotBlank @Pattern(regexp = "^[A-Z0-9_.-]{1,64}$") String reasonCode,
      @Min(5) @Max(300) int ttlSeconds) {}

  public record RenewSafetyLeaseRequest(@Min(5) @Max(300) int ttlSeconds) {}

  public record SafetyLeaseView(
      String leaseId,
      String sessionId,
      long contextEpoch,
      ApplicationSafetySignalType signalType,
      String reasonCode,
      String ownerActorId,
      String state,
      Instant acquiredAt,
      Instant renewedAt,
      Instant expiresAt,
      Instant releasedAt) {}

  public record SafetyLeaseListResponse(List<SafetyLeaseView> items, long total) {}
}
