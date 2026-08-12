package io.browsercloud.api;

import java.util.List;

public final class RemoteDesktopParticipantHistoryModels {
  private RemoteDesktopParticipantHistoryModels() {}

  public record RemoteDesktopParticipantHistoryPage(
      List<RemoteDesktopParticipantModels.RemoteDesktopParticipantView> items,
      long total,
      int limit,
      String nextCursor,
      boolean hasMore) {
    public RemoteDesktopParticipantHistoryPage {
      items = List.copyOf(items);
    }
  }
}
