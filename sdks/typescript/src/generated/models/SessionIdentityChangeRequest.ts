/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { SessionIdentitySpecInput } from './SessionIdentitySpecInput.js';
export type SessionIdentityChangeRequest = {
    requestId: string;
    sessionId: string;
    expectedVersion: number;
    proposedSpecHash: string;
    proposedSpec: SessionIdentitySpecInput;
    reason: string;
    state: 'PENDING' | 'APPROVED' | 'REJECTED' | 'APPLIED' | 'STALE';
    createdBy: string;
    decidedBy: string | null;
    createdAt: string;
    decidedAt: string | null;
    appliedAt: string | null;
};
