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
    /**
     * Monotonic bytes successfully forwarded by the real RFB data path.
     */
    forwardedBytes: number;
    quotaWaitMillis: number;
    throttledBatches: number;
    egressCostUsd: number;
    /**
     * Bytes retained for reconciliation because no effective enterprise rate existed.
     */
    unpricedForwardedBytes: number;
    lastCostPricingVersion?: string | null;
    lastEgressGibUsd?: number | null;
};
