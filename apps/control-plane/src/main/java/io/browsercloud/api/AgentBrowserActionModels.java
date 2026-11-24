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

  public enum ExecutionMode {
    MANAGED,
    DIRECT
  }

  public record ExecuteActionsRequest(
      @NotBlank @Size(max = 2_000) String goal,
      @NotBlank @Size(max = 256) String expectedStateCursor,
      @NotNull @Valid @Size(max = 20) List<CreateAgentTaskRequest.BatchActionRequest> actions,
      Boolean stopOnError,
      ExecutionMode executionMode,
      @Size(max = 2048) String navigateUrl) {
    public ExecuteActionsRequest(
        String goal,
        String expectedStateCursor,
        List<CreateAgentTaskRequest.BatchActionRequest> actions,
        Boolean stopOnError) {
      this(goal, expectedStateCursor, actions, stopOnError, ExecutionMode.MANAGED, null);
    }

    public ExecuteActionsRequest(
        String goal,
        String expectedStateCursor,
        List<CreateAgentTaskRequest.BatchActionRequest> actions,
        Boolean stopOnError,
        ExecutionMode executionMode) {
      this(goal, expectedStateCursor, actions, stopOnError, executionMode, null);
    }

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
