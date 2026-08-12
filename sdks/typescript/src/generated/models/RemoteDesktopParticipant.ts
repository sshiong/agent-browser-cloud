/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RemoteDesktopParticipant = {
    connectionId: string;
    sessionId: string;
    contextEpoch: number;
    actorId?: string | null;
    accessMode?: 'COLLABORATIVE' | 'EXCLUSIVE_TAKEOVER';
    viewOnly?: boolean | null;
    state: 'CONNECTED' | 'REVOKE_REQUESTED' | 'REVOKED' | 'DISCONNECTED';
    reason: string;
    connectedAt?: string | null;
    disconnectedAt?: string | null;
    revokedBy?: string | null;
    revokeRequestedAt?: string | null;
    observedAt: string;
    updatedAt: string;
};
