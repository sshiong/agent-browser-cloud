package io.browsercloud.api;

import java.math.BigDecimal;
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
      Instant updatedAt,
      long forwardedBytes,
      long quotaWaitMillis,
      long throttledBatches,
      BigDecimal egressCostUsd,
      long unpricedForwardedBytes,
      String lastCostPricingVersion,
      BigDecimal lastEgressGibUsd) {}

  public record RemoteDesktopParticipantListResponse(
      List<RemoteDesktopParticipantView> items, int onlineCount) {}
}
