package io.browsercloud.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** API models for atomic, tenant-scoped Environment deletion. */
public final class SessionDeletionModels {

  public record BatchDeleteSessionsRequest(
      @NotNull @Size(min = 1, max = 100)
          List<@Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String> sessionIds) {}

  public record BatchDeleteSessionsResponse(
      String deletionId, int deletedCount, List<String> sessionIds, Instant deletedAt) {}

  private SessionDeletionModels() {}
}
