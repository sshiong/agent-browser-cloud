/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ProviderEvidenceRequirement = {
    type: 'ACCOUNT' | 'TENANT_WORKSPACE' | 'PERMISSION' | 'BUSINESS_ENTITY';
    key: string;
    providerId: string;
    /**
     * SHA-256 of the expected minimized account, scope, workspace or entity value.
     */
    expectedValueHash: string;
    maxAgeSeconds: number;
};
