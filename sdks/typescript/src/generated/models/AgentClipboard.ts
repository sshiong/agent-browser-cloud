/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentClipboard = {
    sessionId: string;
    version: number;
    contentHash: string | null;
    valueLength: number;
    /**
     * AgentClipboard only. This never reflects the VNC/X11 UserClipboard.
     */
    value: string | null;
    updatedAt: string | null;
};
