package io.browsercloud.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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

/** Creation-time Browser identity inputs and approval-backed changes. */
public final class SessionIdentityModels {

  private SessionIdentityModels() {}

  public enum WebRtcPolicy {
    DEFAULT,
    DISABLED,
    PROXY_ONLY
  }

  public enum DnsPolicy {
    SYSTEM,
    PROXY
  }

  public enum ChangeState {
    PENDING,
    APPROVED,
    REJECTED,
    APPLIED,
    STALE
  }

  public record SessionIdentitySpecRequest(
      @Pattern(regexp = "^[^\\p{Cntrl}]{1,512}$") @Size(max = 512) String userAgent,
      @Pattern(regexp = "^(?:UTC|[A-Za-z_+-]+(?:/[A-Za-z0-9_+.-]+)+)$") @Size(max = 128)
          String timezone,
      @Pattern(regexp = "^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$") @Size(max = 64) String locale,
      @Size(max = 16)
          List<@Pattern(regexp = "^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$") @Size(max = 64) String>
              languages,
      WebRtcPolicy webRtcPolicy,
      DnsPolicy dnsPolicy,
      @Min(320) @Max(7680) Integer viewportWidth,
      @Min(240) @Max(4320) Integer viewportHeight,
      @Min(320) @Max(7680) Integer screenWidth,
      @Min(240) @Max(4320) Integer screenHeight,
      @DecimalMin("0.5") @DecimalMax("4.0") BigDecimal deviceScaleFactor,
      @Pattern(regexp = "^[A-Za-z0-9._-]{1,128}$") String fingerprintProfile,
      @Pattern(regexp = "^[A-Za-z0-9._-]{1,128}$") String operatingSystemProfile) {
    public SessionIdentitySpecRequest {
      languages = languages == null ? List.of() : List.copyOf(languages);
    }

    @AssertTrue(message = "viewport width and height must be provided together")
    public boolean hasCompleteViewport() {
      return (viewportWidth == null) == (viewportHeight == null);
    }

    @AssertTrue(message = "screen width and height must be provided together")
    public boolean hasCompleteScreen() {
      return (screenWidth == null) == (screenHeight == null);
    }

    @AssertTrue(message = "viewport must fit inside the declared screen")
    public boolean viewportFitsScreen() {
      return viewportWidth == null
          || screenWidth == null
          || (viewportWidth <= screenWidth && viewportHeight <= screenHeight);
    }

    @AssertTrue(message = "languages must contain unique values")
    public boolean hasUniqueLanguages() {
      return languages.size() == languages.stream().distinct().count();
    }

    @AssertTrue(message = "timezone must be a supported IANA zone")
    public boolean hasSupportedTimezone() {
      if (timezone == null) return true;
      try {
        java.time.ZoneId.of(timezone);
        return true;
      } catch (java.time.DateTimeException exception) {
        return false;
      }
    }
  }

  public record SessionIdentitySpecView(
      String sessionId,
      long version,
      String specHash,
      boolean locked,
      SessionIdentitySpecRequest spec,
      Instant lockedAt,
      Instant updatedAt) {}

  public record CreateSessionIdentityChangeRequest(
      @Min(1) long expectedVersion,
      @NotNull @Valid SessionIdentitySpecRequest proposedSpec,
      @NotBlank @Size(max = 1000) String reason) {}

  public record SessionIdentityChangeRequestView(
      String requestId,
      String sessionId,
      long expectedVersion,
      String proposedSpecHash,
      SessionIdentitySpecRequest proposedSpec,
      String reason,
      ChangeState state,
      String createdBy,
      String decidedBy,
      Instant createdAt,
      Instant decidedAt,
      Instant appliedAt) {}
}
