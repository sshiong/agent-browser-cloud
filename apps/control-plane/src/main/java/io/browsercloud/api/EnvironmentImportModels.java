package io.browsercloud.api;

import io.browsercloud.domain.agent.AgentPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class EnvironmentImportModels {
  private EnvironmentImportModels() {}

  public enum EnvironmentImportState {
    VALIDATED,
    INVALID,
    EXECUTING,
    COMMITTED
  }

  public enum EnvironmentImportValidationState {
    READY,
    INVALID
  }

  public enum EnvironmentImportExecutionState {
    PENDING,
    SUCCEEDED
  }

  public record EnvironmentImportSpec(
      @NotBlank @Size(max = 96) String displayName,
      @Size(max = 512) String description,
      @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_-]{1,128}$") String profileId,
      @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String runtimeBuildId,
      @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String applicationId,
      @Pattern(regexp = "^grp_[a-zA-Z0-9]{16,32}$") String groupId,
      @Size(max = 16) List<@NotBlank @Pattern(regexp = "^tag_[a-zA-Z0-9]{16,32}$") String> tagIds,
      @Pattern(regexp = "^[a-z0-9-]{1,32}$") String region,
      @Valid ResourcePolicyRequest resourcePolicy,
      @Min(0) @Max(64) int requestedTabs,
      @Min(0) @Max(600) int agentActionsPerMinute,
      boolean remoteDesktop,
      Boolean humanTakeoverEnabled,
      AgentPolicy agentPolicy,
      boolean web3Workload,
      boolean mediaWorkload,
      @Min(0) @Max(32) int requestedMediaStreams,
      @Min(0) @Max(1_000_000) int mediaBitrateKbps,
      boolean videoRecording,
      @Size(max = 32)
          List<@NotBlank @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String> extensionIds) {

    @AssertTrue(message = "tagIds must contain unique values")
    public boolean hasUniqueTagIds() {
      return tagIds == null || tagIds.size() == tagIds.stream().distinct().count();
    }

    @AssertTrue(message = "extensionIds must contain unique values")
    public boolean hasUniqueExtensionIds() {
      return extensionIds == null
          || extensionIds.size() == extensionIds.stream().distinct().count();
    }
  }

  public record PreviewEnvironmentImportRequest(
      @Min(1) @Max(1) int schemaVersion,
      @NotBlank @Size(max = 96) String name,
      @NotNull @Size(min = 1, max = 25) List<@Valid EnvironmentImportSpec> environments) {}

  public record CommitEnvironmentImportRequest(@Min(0) long expectedVersion) {}

  public record EnvironmentImportItemView(
      String itemId,
      int itemIndex,
      EnvironmentImportSpec specification,
      EnvironmentImportValidationState validationState,
      List<String> validationErrors,
      EnvironmentImportExecutionState executionState,
      String sessionId,
      String operationId,
      String requestId,
      Instant updatedAt) {}

  public record EnvironmentImportView(
      String importId,
      String name,
      int schemaVersion,
      String manifestHash,
      EnvironmentImportState state,
      int totalCount,
      int readyCount,
      int succeededCount,
      List<EnvironmentImportItemView> items,
      Instant createdAt,
      Instant updatedAt,
      Instant committedAt,
      long version) {}

  public record EnvironmentImportListItem(
      String importId,
      String name,
      EnvironmentImportState state,
      int totalCount,
      int readyCount,
      int succeededCount,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  public record EnvironmentImportListResponse(List<EnvironmentImportListItem> items, int total) {}
}
