/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RemoteDesktopConnection = {
    /**
     * Same-origin WebSocket path. The embedded ticket is single-use and expires quickly.
     */
    webSocketPath: string;
    expiresAt: string;
    protocol: 'rfb';
    operationEpoch: number;
    viewOnly: boolean;
};
