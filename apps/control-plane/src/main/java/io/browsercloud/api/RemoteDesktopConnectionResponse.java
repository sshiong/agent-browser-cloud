package io.browsercloud.api;

import java.time.Instant;

/** 短期、单次使用的远程桌面连接描述。 */
public record RemoteDesktopConnectionResponse(
    String connectionId,
    String webSocketPath,
    Instant expiresAt,
    String protocol,
    long operationEpoch,
    boolean viewOnly) {}
