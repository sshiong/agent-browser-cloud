/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ProviderEvidence = {
    evidenceId: string;
    sessionId: string;
    applicationId: string;
    contractVersion: number;
    contextEpoch: number;
    stateVersion: number;
    type: 'ACCOUNT' | 'TENANT_WORKSPACE' | 'PERMISSION' | 'BUSINESS_ENTITY';
    key: string;
    providerId: string;
    outcome: 'MATCH' | 'MISMATCH' | 'UNKNOWN';
    valueHashMatched: boolean;
    providerReferenceHash: string;
    adapterActorId: string;
    requestId: string;
    observedAt: string;
    expiresAt: string;
    createdAt: string;
};
