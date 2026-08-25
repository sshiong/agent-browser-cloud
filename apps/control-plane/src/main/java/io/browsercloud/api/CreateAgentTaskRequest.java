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
    @Min(1) @Max(23) Integer maxActions,
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
      @Pattern(regexp = "^ais_[A-Za-z0-9]{20,32}$") String secretId,
      ActionDataClass dataClass,
      @Min(-2_000) @Max(2_000) Integer scrollDeltaY,
      WaitCondition waitCondition,
      @Min(100) @Max(10_000) Integer timeoutMs,
      @Valid @Size(max = 20) List<BatchActionRequest> actions,
      Boolean stopOnError,
      @Size(max = 128) String tabId,
      @Size(max = 8_192) String tabUrl,
      @Pattern(regexp = "^dlg_[0-9a-f]{20}$") String dialogId,
      @Size(max = 128) String endTargetRef,
      @Size(max = 32) String key,
      @Min(0) @Max(2) Integer button,
      @Min(-4_000) @Max(4_000) Integer deltaX,
      @Min(-4_000) @Max(4_000) Integer deltaY,
      @Min(0) @Max(5_000) Integer durationMs) {
    public ActionRequest(
        ToolId toolId,
        String targetRef,
        Long targetRevision,
        String value,
        String secretId,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs) {
      this(
          toolId,
          targetRef,
          targetRevision,
          value,
          secretId,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          List.of(),
          true,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public ActionRequest(
        ToolId toolId,
        String targetRef,
        Long targetRevision,
        String value,
        String secretId,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs,
        List<BatchActionRequest> actions,
        Boolean stopOnError,
        String tabId,
        String tabUrl,
        String dialogId) {
      this(
          toolId,
          targetRef,
          targetRevision,
          value,
          secretId,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          actions,
          stopOnError,
          tabId,
          tabUrl,
          dialogId,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public ActionRequest(
        ToolId toolId,
        String targetRef,
        Long targetRevision,
        String value,
        String secretId,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs,
        List<BatchActionRequest> actions,
        Boolean stopOnError,
        String tabId,
        String tabUrl) {
      this(
          toolId,
          targetRef,
          targetRevision,
          value,
          secretId,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          actions,
          stopOnError,
          tabId,
          tabUrl,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public ActionRequest(
        ToolId toolId,
        String targetRef,
        Long targetRevision,
        String value,
        String secretId,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs,
        List<BatchActionRequest> actions,
        Boolean stopOnError) {
      this(
          toolId,
          targetRef,
          targetRevision,
          value,
          secretId,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          actions,
          stopOnError,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public ActionRequest {
      actions = actions == null ? List.of() : List.copyOf(actions);
    }
  }

  public record BatchActionRequest(
      @NotNull ToolId toolId,
      @Size(max = 128) String targetRef,
      @Min(1) Long targetRevision,
      @Size(max = 2_000) String value,
      @Pattern(regexp = "^ais_[A-Za-z0-9]{20,32}$") String secretId,
      ActionDataClass dataClass,
      @Min(-2_000) @Max(2_000) Integer scrollDeltaY,
      WaitCondition waitCondition,
      @Min(100) @Max(10_000) Integer timeoutMs,
      @Size(max = 128) String tabId,
      @Size(max = 8_192) String tabUrl,
      @Pattern(regexp = "^dlg_[0-9a-f]{20}$") String dialogId,
      @Size(max = 128) String endTargetRef,
      @Size(max = 32) String key,
      @Min(0) @Max(2) Integer button,
      @Min(-4_000) @Max(4_000) Integer deltaX,
      @Min(-4_000) @Max(4_000) Integer deltaY,
      @Min(0) @Max(5_000) Integer durationMs) {
    public BatchActionRequest(
        ToolId toolId,
        String targetRef,
        Long targetRevision,
        String value,
        String secretId,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs) {
      this(
          toolId,
          targetRef,
          targetRevision,
          value,
          secretId,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public BatchActionRequest(
        ToolId toolId,
        String targetRef,
        Long targetRevision,
        String value,
        String secretId,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs,
        String tabId,
        String tabUrl,
        String dialogId) {
      this(
          toolId,
          targetRef,
          targetRevision,
          value,
          secretId,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          tabId,
          tabUrl,
          dialogId,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public BatchActionRequest(
        ToolId toolId,
        String targetRef,
        Long targetRevision,
        String value,
        String secretId,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs,
        String tabId,
        String tabUrl) {
      this(
          toolId,
          targetRef,
          targetRevision,
          value,
          secretId,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          tabId,
          tabUrl,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }
  }
}
