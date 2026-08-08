/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ProxyBindingRequest = {
    name: string;
    description?: string | null;
    providerId: string;
    region?: string | null;
    /**
     * Must resolve to an exit configured for the selected provider.
     */
    expectedExitIp: string;
    /**
     * Required on create. Omit on update to preserve the write-only Secret Manager reference.
     */
    credentialRef?: string | null;
    enabled: boolean;
    /**
     * Required on update for compare-and-swap.
     */
    expectedVersion?: number | null;
};
