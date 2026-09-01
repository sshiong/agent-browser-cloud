package io.browsercloud.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AgentClipboardBridgeModels {
  private AgentClipboardBridgeModels() {}

  public enum ClipboardBridgeDirection {
    USER_TO_AGENT,
    AGENT_TO_USER
  }

  public enum ClipboardBridgePurpose {
    OPERATOR_COPY,
    AUTOMATION_HANDOFF,
    HUMAN_ASSISTANCE
  }

  public record CreateClipboardBridgeRequest(
      @NotNull ClipboardBridgeDirection direction,
      @NotNull ClipboardBridgePurpose purpose,
      @NotNull @Pattern(regexp = "^rdc_[A-Za-z0-9]{20}$") String connectionId,
      @Min(0) @Max(Long.MAX_VALUE) long expectedAgentClipboardVersion,
      @Size(min = 1, max = 2_000) String value,
      Instant userClipboardObservedAt) {

    @AssertTrue(message = "clipboard bridge direction and payload do not match")
    public boolean hasValidPayload() {
      if (direction == null) return false;
      return switch (direction) {
        case USER_TO_AGENT -> value != null && !value.isEmpty() && userClipboardObservedAt != null;
        case AGENT_TO_USER -> value == null && userClipboardObservedAt == null;
      };
    }
  }

  public record CompleteClipboardBridgeRequest(
      @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String contentHash) {}

  public record ClipboardBridgeView(
      String bridgeId,
      String sessionId,
      ClipboardBridgeDirection direction,
      ClipboardBridgePurpose purpose,
      String connectionId,
      String state,
      long agentClipboardVersion,
      String contentHash,
      int valueLength,
      String value,
      Instant expiresAt,
      Instant completedAt,
      Instant createdAt) {}
}
