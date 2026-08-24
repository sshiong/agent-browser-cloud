package io.browsercloud.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Metadata-only browser.screenshot contract. Pixels are redeemed through one-time grants. */
public final class AgentBrowserScreenshotModels {
  private AgentBrowserScreenshotModels() {}

  public enum ScreenshotMode {
    VIEWPORT,
    FULL_PAGE,
    ELEMENT,
    REGION,
    CHALLENGE_REGION
  }

  public record ScreenshotRegion(
      @DecimalMin("0.0") @DecimalMax("7680.0") double x,
      @DecimalMin("0.0") @DecimalMax("4320.0") double y,
      @DecimalMin("1.0") @DecimalMax("7680.0") double width,
      @DecimalMin("1.0") @DecimalMax("4320.0") double height) {}

  public record CaptureScreenshotRequest(
      @NotNull ScreenshotMode mode,
      @NotBlank @Pattern(regexp = "^[0-9]+:[0-9]+:[a-f0-9]{64}$") String expectedStateCursor,
      @Size(max = 256) String elementId,
      @Valid ScreenshotRegion region) {

    @AssertTrue(message = "screenshot mode and target fields do not match")
    public boolean hasValidModeTarget() {
      if (mode == null) return false;
      return switch (mode) {
        case VIEWPORT, FULL_PAGE -> (elementId == null || elementId.isBlank()) && region == null;
        case ELEMENT -> elementId != null && !elementId.isBlank() && region == null;
        case REGION, CHALLENGE_REGION ->
            (elementId == null || elementId.isBlank()) && region != null;
      };
    }
  }

  public record ScreenshotView(
      String screenshotId,
      String sessionId,
      ScreenshotMode mode,
      String state,
      String expectedStateCursor,
      String capturedStateCursor,
      String activeTabId,
      String elementId,
      ScreenshotRegion region,
      String coordinateSpace,
      Double viewportWidth,
      Double viewportHeight,
      Double deviceScaleFactor,
      String evidenceId,
      String accessGrantId,
      Instant accessGrantExpiresAt,
      String contentSha256,
      Long contentBytes,
      String redactionState,
      Integer redactedRegionCount,
      String errorCode,
      String requestId,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt) {}
}
