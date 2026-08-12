package io.browsercloud.api;

import java.time.Instant;

/** 短期、单次使用的远程桌面连接描述。 */
public record RemoteDesktopConnectionResponse(
    String connectionId,
    String webSocketPath,
    Instant expiresAt,
    String protocol,
    long operationEpoch,
    boolean viewOnly,
    int actorBitrateLimitKbps,
    int actorFrameRateLimitFps) {

  public RemoteDesktopConnectionResponse(
      String connectionId,
      String webSocketPath,
      Instant expiresAt,
      String protocol,
      long operationEpoch,
      boolean viewOnly) {
    this(
        connectionId,
        webSocketPath,
        expiresAt,
        protocol,
        operationEpoch,
        viewOnly,
        viewOnly ? 4_000 : 8_000,
        viewOnly ? 15 : 30);
  }
}
