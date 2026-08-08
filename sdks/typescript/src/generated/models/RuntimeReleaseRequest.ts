/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RuntimeReleaseRequest = {
    releaseId: string;
    buildId: string;
    targetChannel: 'CANARY' | 'STABLE' | 'DISABLED';
    reason: string;
    state: 'REQUESTED' | 'APPROVED' | 'REJECTED';
    requestedBy: string;
    approvedBy?: string | null;
    rejectedBy?: string | null;
    requestedAt: string;
    decidedAt?: string | null;
    evidenceHash?: string | null;
};
