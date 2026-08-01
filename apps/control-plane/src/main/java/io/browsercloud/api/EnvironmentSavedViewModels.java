package io.browsercloud.api;

import io.browsercloud.domain.session.SessionState;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

  public enum SavedViewTagMatch {
    ANY,
    ALL
  }

  public record CreateEnvironmentSavedViewRequest(
      @NotBlank @Size(max = 64) String name,
      @NotNull SavedViewScope scope,
      @NotNull EnvironmentPrimaryView primaryView,
      SessionState sessionState,
      @Size(max = 128) String searchQuery,
      @Pattern(regexp = "^grp_[a-zA-Z0-9]{16,32}$") String groupId,
      @Size(max = 16) List<@NotBlank @Pattern(regexp = "^tag_[a-zA-Z0-9]{16,32}$") String> tagIds,
      SavedViewTagMatch tagMatch,
      boolean showRuntimeColumn,
      boolean showContextColumn,
      boolean showOperationColumn) {

    @AssertTrue(message = "tagIds must contain unique values")
    public boolean hasUniqueTagIds() {
      return unique(tagIds);
    }
  }

  public record UpdateEnvironmentSavedViewRequest(
      @Min(0) long expectedVersion,
      @NotBlank @Size(max = 64) String name,
      @NotNull EnvironmentPrimaryView primaryView,
      SessionState sessionState,
      @Size(max = 128) String searchQuery,
      @Pattern(regexp = "^grp_[a-zA-Z0-9]{16,32}$") String groupId,
      @Size(max = 16) List<@NotBlank @Pattern(regexp = "^tag_[a-zA-Z0-9]{16,32}$") String> tagIds,
      SavedViewTagMatch tagMatch,
      boolean showRuntimeColumn,
      boolean showContextColumn,
      boolean showOperationColumn) {

    @AssertTrue(message = "tagIds must contain unique values")
    public boolean hasUniqueTagIds() {
      return unique(tagIds);
    }
  }

  public record EnvironmentSavedViewView(
      String savedViewId,
      String name,
      SavedViewScope scope,
      String ownerActorId,
      EnvironmentPrimaryView primaryView,
      SessionState sessionState,
      String searchQuery,
      String groupId,
      List<String> tagIds,
      SavedViewTagMatch tagMatch,
      boolean showRuntimeColumn,
      boolean showContextColumn,
      boolean showOperationColumn,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  public record EnvironmentSavedViewListResponse(List<EnvironmentSavedViewView> items, int total) {}

  private static boolean unique(List<String> values) {
    return values == null || values.size() == values.stream().distinct().count();
  }
}
