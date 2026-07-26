package io.browsercloud.api;

import io.browsercloud.domain.agent.AgentModels.ActionDataClass;
import io.browsercloud.domain.agent.AgentModels.InstructionSourceType;
import io.browsercloud.domain.agent.AgentModels.ToolId;
import io.browsercloud.domain.agent.AgentModels.WaitCondition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateAgentTaskRequest(
    @NotBlank @Size(max = 2_000) String goal,
    @Size(max = 2_048) String startUrl,
    @NotNull @Size(min = 1, max = 20)
        List<@NotBlank @Size(max = 253) @Pattern(regexp = "^[A-Za-z0-9.-]+$") String>
            allowedDomains,
    @Min(1) @Max(20) Integer maxActions,
    @Min(0) @Max(3) Integer replanBudget,
    @Valid @Size(max = 20) List<InstructionSourceRequest> contextSources,
    @Valid @Size(max = 10) List<ActionRequest> actions) {

  public record InstructionSourceRequest(
      @NotBlank @Size(max = 128) String sourceId,
      @NotNull InstructionSourceType sourceType,
      @NotBlank @Size(max = 64) String classification,
      @NotBlank @Size(max = 10_000) String content) {}

  public record ActionRequest(
      @NotNull ToolId toolId,
      @Size(max = 128) String targetRef,
      @Min(1) Long targetRevision,
      @Size(max = 2_000) String value,
      ActionDataClass dataClass,
      @Min(-2_000) @Max(2_000) Integer scrollDeltaY,
      WaitCondition waitCondition,
      @Min(100) @Max(10_000) Integer timeoutMs) {}
}
