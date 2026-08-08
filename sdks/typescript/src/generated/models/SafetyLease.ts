/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type SafetyLease = {
    leaseId: string;
    sessionId: string;
    contextEpoch: number;
    signalType: 'FILE_TRANSFER' | 'FORM_SUBMISSION' | 'PAYMENT_OR_SECURITY' | 'CRITICAL_TRANSACTION' | 'BUSINESS_RECOVERY_UNKNOWN';
    reasonCode: string;
    ownerActorId: string;
    state: 'ACTIVE' | 'RELEASED' | 'EXPIRED';
    acquiredAt: string;
    renewedAt: string;
    expiresAt: string;
    releasedAt?: string | null;
};
