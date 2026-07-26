package io.browsercloud.api;

import java.util.List;

public record KeyRotationRequestListResponse(List<KeyRotationRequestView> items, int total) {
  public KeyRotationRequestListResponse {
    items = List.copyOf(items);
  }
}
