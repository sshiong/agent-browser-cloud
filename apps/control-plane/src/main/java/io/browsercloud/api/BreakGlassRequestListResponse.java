package io.browsercloud.api;

import java.util.List;

public record BreakGlassRequestListResponse(List<BreakGlassRequestView> items, int total) {
  public BreakGlassRequestListResponse {
    items = List.copyOf(items);
  }
}
