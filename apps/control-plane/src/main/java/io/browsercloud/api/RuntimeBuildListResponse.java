package io.browsercloud.api;

import java.util.List;

public record RuntimeBuildListResponse(List<RuntimeBuildView> items, int total) {
  public RuntimeBuildListResponse {
    items = List.copyOf(items);
  }
}
