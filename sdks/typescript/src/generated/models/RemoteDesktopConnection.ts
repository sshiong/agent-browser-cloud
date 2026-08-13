/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RemoteDesktopConnection = {
    /**
     * Non-secret connection identity used for participant governance.
     */
    connectionId: string;
    /**
     * Same-origin WebSocket path. The embedded ticket is single-use and expires quickly.
     */
    webSocketPath: string;
    expiresAt: string;
    protocol: 'rfb';
    /**
     * Compatibility field containing the Session Context Epoch. Its presence never implies an exclusive Operation or permission to stop the Agent.
     */
    operationEpoch: number;
    viewOnly: boolean;
    /**
     * Server-signed outbound bandwidth ceiling shared by this actor's connections.
     */
    actorBitrateLimitKbps?: number;
    /**
     * Server-signed forwarding frequency ceiling shared by this actor's connections.
     */
    actorFrameRateLimitFps?: number;
};
