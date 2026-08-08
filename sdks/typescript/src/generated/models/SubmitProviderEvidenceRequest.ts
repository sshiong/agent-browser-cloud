/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type SubmitProviderEvidenceRequest = {
    contextEpoch: number;
    stateVersion: number;
    type: 'ACCOUNT' | 'TENANT_WORKSPACE' | 'PERMISSION' | 'BUSINESS_ENTITY';
    key: string;
    providerId: string;
    observedValueHash: string;
    outcome: 'MATCH' | 'MISMATCH' | 'UNKNOWN';
    providerReference: string;
    observedAt: string;
};
