package io.browsercloud.api;

import java.util.List;

public record AgentTaskSummaryListResponse(
    List<AgentTaskSummaryView> items,
    Metrics metrics,
    long total,
    int limit,
    String nextCursor,
    boolean hasMore) {

  public AgentTaskSummaryListResponse {
    items = List.copyOf(items);
  }

  public record Metrics(long planned, long completed, long blocked) {}
}
