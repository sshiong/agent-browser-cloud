/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type SecureDebugSession = {
    debugSessionId: string;
    breakGlassRequestId: string;
    resourceType: 'SESSION';
    resourceId: string;
    operatorId: string;
    state: 'ACTIVE' | 'ENDED' | 'EXPIRED' | 'REVOKED';
    startedAt: string;
    expiresAt: string;
    endedAt: string | null;
    endReason: string | null;
    accessCount: number;
    lastAccessAt: string | null;
    evidenceHeadHash: string | null;
};
