package io.browsercloud.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class WorkspaceBatchOperationModels {
  private WorkspaceBatchOperationModels() {}

  public enum WorkspaceBatchAction {
    START,
    PAUSE_AGENT,
    MIGRATE,
    HIBERNATE
  }

  public enum WorkspaceBatchState {
    ACCEPTED,
    EXECUTING,
    CANCELLING,
    SUCCEEDED,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED
  }

  public enum WorkspaceBatchItemState {
    ACCEPTED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    CANCELLED
  }

  public enum TagMatch {
    ANY,
    ALL
  }

  public record WorkspaceBatchSelector(
      @Pattern(regexp = "^grp_[a-zA-Z0-9]{16,32}$") String groupId,
      @Size(max = 16) List<@Pattern(regexp = "^tag_[a-zA-Z0-9]{16,32}$") String> tagIds,
      TagMatch tagMatch,
      @Size(max = 100) List<@Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String> sessionIds) {}

  public record CreateWorkspaceBatchOperationRequest(
      @NotNull WorkspaceBatchAction action,
      @NotNull @Valid WorkspaceBatchSelector selector,
      @Size(max = 240) String reason,
      boolean confirmed) {}

  public record WorkspaceBatchOperationItemView(
      String batchItemId,
      String sessionId,
      int ordinal,
      String commandId,
      WorkspaceBatchItemState state,
      String childOperationId,
      String failureCode,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt) {}

  public record WorkspaceBatchOperationView(
      String batchOperationId,
      WorkspaceBatchAction action,
      WorkspaceBatchState state,
      WorkspaceBatchSelector selector,
      String reason,
      int total,
      int accepted,
      int executing,
      int succeeded,
      int failed,
      int cancelled,
      boolean cancellationRequested,
      List<WorkspaceBatchOperationItemView> items,
      String actorId,
      Instant createdAt,
      Instant updatedAt) {}

  public record WorkspaceBatchOperationListResponse(
      List<WorkspaceBatchOperationView> items, int total) {}

  public record CancelWorkspaceBatchOperationRequest(
      @NotBlank @Size(min = 8, max = 240) String reason) {}
}
