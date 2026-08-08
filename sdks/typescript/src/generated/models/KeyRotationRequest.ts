/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type KeyRotationRequest = {
    rotationId: string;
    keyScope: string;
    oldKeyId: string;
    newKeyId: string;
    rotationTrigger: string;
    reason: string;
    requestedOverlapMinutes: number;
    state: 'REQUESTED' | 'ROTATING' | 'COMPLETED' | 'REVOKED' | 'FAILED';
    requestedBy: string;
    approvedBy?: string | null;
    completedBy?: string | null;
    revokedBy?: string | null;
    requestedAt: string;
    approvedAt?: string | null;
    startedAt?: string | null;
    completedAt?: string | null;
    revokedAt?: string | null;
    overlapUntil?: string | null;
    progressPercent: number;
    newKeyWriteVerified?: boolean | null;
    oldKeyReadVerified?: boolean | null;
    plaintextRejected?: boolean | null;
    affectedWorkloads?: number | null;
    verificationReference?: string | null;
    approvalEvidenceHash?: string | null;
    completionEvidenceHash?: string | null;
};
