package io.browsercloud.api;

import io.browsercloud.domain.session.SessionState;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class EnvironmentSavedViewModels {
  private EnvironmentSavedViewModels() {}

  public enum SavedViewScope {
    PERSONAL,
    WORKSPACE
  }

  public enum EnvironmentPrimaryView {
    ALL,
    RUNNING,
    STOPPED,
    ABNORMAL
  }

  public record CreateEnvironmentSavedViewRequest(
      @NotBlank @Size(max = 64) String name,
      @NotNull SavedViewScope scope,
      @NotNull EnvironmentPrimaryView primaryView,
      SessionState sessionState,
      @Size(max = 128) String searchQuery,
      boolean showRuntimeColumn,
      boolean showContextColumn,
      boolean showOperationColumn) {}

  public record UpdateEnvironmentSavedViewRequest(
      @Min(0) long expectedVersion,
      @NotBlank @Size(max = 64) String name,
      @NotNull EnvironmentPrimaryView primaryView,
      SessionState sessionState,
      @Size(max = 128) String searchQuery,
      boolean showRuntimeColumn,
      boolean showContextColumn,
      boolean showOperationColumn) {}

  public record EnvironmentSavedViewView(
      String savedViewId,
      String name,
      SavedViewScope scope,
      String ownerActorId,
      EnvironmentPrimaryView primaryView,
      SessionState sessionState,
      String searchQuery,
      boolean showRuntimeColumn,
      boolean showContextColumn,
      boolean showOperationColumn,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  public record EnvironmentSavedViewListResponse(List<EnvironmentSavedViewView> items, int total) {}
}
