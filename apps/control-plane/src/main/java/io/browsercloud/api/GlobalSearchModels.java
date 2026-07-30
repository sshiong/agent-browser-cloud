package io.browsercloud.api;

import java.time.Instant;
import java.util.List;

public final class GlobalSearchModels {
  private GlobalSearchModels() {}

  public enum SearchResourceType {
    SESSION,
    PROFILE,
    GROUP,
    TAG,
    RUNTIME,
    NODE
  }

  public record GlobalSearchResult(
      SearchResourceType resourceType,
      String resourceId,
      String title,
      String description,
      String status,
      String region,
      Instant updatedAt) {}

  public record GlobalSearchResponse(
      String query, List<GlobalSearchResult> items, int limit, boolean truncated) {}
}
