/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { EvidencePurpose } from './EvidencePurpose.js';
export type EvidenceAccessGrant = {
    grantId: string;
    sessionId: string;
    evidenceId: string;
    purpose: EvidencePurpose;
    state: 'ISSUED' | 'REDEEMING' | 'REDEEMED' | 'FAILED';
    expiresAt: string;
    createdAt: string;
    redeemedAt?: string | null;
    errorCode?: string | null;
    requestId?: string | null;
};
