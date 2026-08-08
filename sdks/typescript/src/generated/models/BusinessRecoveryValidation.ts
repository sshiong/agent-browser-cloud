/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type BusinessRecoveryValidation = {
    validationId: string;
    sessionId: string;
    applicationId?: string | null;
    contractVersion?: number | null;
    contextEpoch: number;
    stateVersion: number;
    verdict: 'READY' | 'READY_WITH_WARNING' | 'LOGIN_REQUIRED' | 'PERMISSION_CHANGED' | 'ACCOUNT_MISMATCH' | 'APPLICATION_UNAVAILABLE' | 'STATE_CHANGED' | 'MANUAL_RECOVERY_REQUIRED';
    ready: boolean;
    evidence: Array<string>;
    source: 'API' | 'MIGRATION';
    requestId: string;
    evaluatedAt: string;
};
