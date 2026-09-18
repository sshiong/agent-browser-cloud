package io.browsercloud.api;

import io.browsercloud.domain.agent.AgentModels.WaitCondition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** One-call state-fenced Browser action gateway. */
public final class AgentBrowserActionModels {
  private AgentBrowserActionModels() {}

  public record ExecuteActionsRequest(
      @NotBlank @Size(max = 2_000) String goal,
      @NotBlank @Size(max = 256) String expectedStateCursor,
      @NotNull @Valid @Size(min = 1, max = 20)
          List<CreateAgentTaskRequest.BatchActionRequest> actions,
      Boolean stopOnError) {
    public ExecuteActionsRequest {
      actions = actions == null ? List.of() : List.copyOf(actions);
    }
  }

  public record WaitRequest(
      @NotBlank @Size(max = 2_000) String goal,
      @NotBlank @Size(max = 256) String expectedStateCursor,
      @NotNull WaitCondition waitCondition,
      @Size(max = 128) String targetRef,
      @NotNull @Min(100) @Max(10_000) Integer timeoutMs) {}

  public record HandoffRequest(
      @NotBlank @Size(max = 2_000) String goal,
      @NotBlank @Size(max = 256) String expectedStateCursor) {}
}
