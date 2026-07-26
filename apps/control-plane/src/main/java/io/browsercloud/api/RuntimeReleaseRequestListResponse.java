package io.browsercloud.api;

import java.util.List;

public record RuntimeReleaseRequestListResponse(List<RuntimeReleaseRequestView> items, int total) {
  public RuntimeReleaseRequestListResponse {
    items = List.copyOf(items);
  }
}
