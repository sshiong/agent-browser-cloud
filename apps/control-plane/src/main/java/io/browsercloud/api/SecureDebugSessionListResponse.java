package io.browsercloud.api;

import java.util.List;

public record SecureDebugSessionListResponse(List<SecureDebugSessionView> items, int total) {
  public SecureDebugSessionListResponse {
    items = List.copyOf(items);
  }
}
