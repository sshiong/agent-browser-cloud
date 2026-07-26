package io.browsercloud.api;

import java.util.List;

public record AuditEventListResponse(
    List<AuditEventView> items, long total, boolean chainValid, String headHash) {

  public AuditEventListResponse {
    items = List.copyOf(items);
  }
}
