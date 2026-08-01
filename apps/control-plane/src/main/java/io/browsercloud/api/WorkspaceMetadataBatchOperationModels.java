package io.browsercloud.api;

import io.browsercloud.api.WorkspaceBatchOperationModels.TagMatch;
import io.browsercloud.api.WorkspaceBatchOperationModels.WorkspaceBatchItemState;
import io.browsercloud.api.WorkspaceBatchOperationModels.WorkspaceBatchState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class WorkspaceMetadataBatchOperationModels {
  private WorkspaceMetadataBatchOperationModels() {}

  public enum WorkspaceMetadataBatchAction {
    ASSIGN_GROUP,
    REMOVE_GROUP,
    ASSIGN_TAGS,
    REMOVE_TAGS
  }

  public record WorkspaceMetadataBatchSelector(
      @Pattern(regexp = "^grp_[a-zA-Z0-9]{16,32}$") String groupId,
      @Size(max = 16) List<@Pattern(regexp = "^tag_[a-zA-Z0-9]{16,32}$") String> tagIds,
      TagMatch tagMatch,
      @Size(max = 100) List<@Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String> sessionIds) {}

  public record WorkspaceMetadataBatchTarget(
      @Pattern(regexp = "^grp_[a-zA-Z0-9]{16,32}$") String groupId,
      @Size(max = 16) List<@Pattern(regexp = "^tag_[a-zA-Z0-9]{16,32}$") String> tagIds) {}

  public record CreateWorkspaceMetadataBatchOperationRequest(
      @NotNull WorkspaceMetadataBatchAction action,
      @NotNull @Valid WorkspaceMetadataBatchSelector selector,
      @NotNull @Valid WorkspaceMetadataBatchTarget target,
      @NotBlank @Size(min = 8, max = 240) String reason,
      boolean confirmed) {}

  public record WorkspaceMetadataBatchOperationItemView(
      String batchItemId,
      String sessionId,
      int ordinal,
      WorkspaceBatchItemState state,
      String failureCode,
      int attempt,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt) {}

  public record WorkspaceMetadataBatchOperationView(
      String batchOperationId,
      WorkspaceMetadataBatchAction action,
      WorkspaceBatchState state,
      WorkspaceMetadataBatchSelector selector,
      WorkspaceMetadataBatchTarget target,
      String reason,
      int total,
      int accepted,
      int executing,
      int succeeded,
      int failed,
      int cancelled,
      boolean cancellationRequested,
      List<WorkspaceMetadataBatchOperationItemView> items,
      String actorId,
      Instant createdAt,
      Instant updatedAt) {}

  public record WorkspaceMetadataBatchOperationListResponse(
      List<WorkspaceMetadataBatchOperationView> items, int total) {}

  public record CancelWorkspaceMetadataBatchOperationRequest(
      @NotBlank @Size(min = 8, max = 240) String reason) {}
}
