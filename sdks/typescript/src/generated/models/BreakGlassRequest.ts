/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type BreakGlassRequest = {
    requestId: string;
    ticketId: string;
    reason: string;
    resourceType: string;
    resourceId: string;
    requestedScope: string;
    state: 'REQUESTED' | 'ACTIVE' | 'REJECTED' | 'REVOKED' | 'EXPIRED';
    requestedBy: string;
    approvedBy: string | null;
    rejectedBy: string | null;
    revokedBy: string | null;
    evidenceHash: string | null;
    requestedAt: string;
    approvedAt: string | null;
    rejectedAt: string | null;
    revokedAt: string | null;
    expiresAt: string;
    reviewedAt: string | null;
};
