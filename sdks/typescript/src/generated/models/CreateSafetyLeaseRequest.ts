/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type CreateSafetyLeaseRequest = {
    signalType: 'FILE_TRANSFER' | 'FORM_SUBMISSION' | 'PAYMENT_OR_SECURITY' | 'CRITICAL_TRANSACTION' | 'BUSINESS_RECOVERY_UNKNOWN';
    reasonCode: string;
    ttlSeconds: number;
};
