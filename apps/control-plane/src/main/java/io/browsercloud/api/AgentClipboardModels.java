package io.browsercloud.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AgentClipboardModels {
  private AgentClipboardModels() {}

  public record AgentClipboardView(
      String sessionId,
      long version,
      String contentHash,
      int valueLength,
      String value,
      Instant updatedAt) {}

  public record WriteAgentClipboardRequest(
      @NotNull @Size(min = 1, max = 2_000) String value,
      @Min(0) @Max(Long.MAX_VALUE) long expectedVersion) {}
}
