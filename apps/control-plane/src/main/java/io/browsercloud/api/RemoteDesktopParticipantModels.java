package io.browsercloud.api;

import java.time.Instant;
import java.util.List;

public final class RemoteDesktopParticipantModels {
  private RemoteDesktopParticipantModels() {}

  public record RemoteDesktopParticipantView(
      String connectionId,
      String sessionId,
      long contextEpoch,
      String actorId,
      String accessMode,
      Boolean viewOnly,
      String state,
      String reason,
      Instant connectedAt,
      Instant disconnectedAt,
      String revokedBy,
      Instant revokeRequestedAt,
      Instant observedAt,
      Instant updatedAt) {}

  public record RemoteDesktopParticipantListResponse(
      List<RemoteDesktopParticipantView> items, int onlineCount) {}
}
