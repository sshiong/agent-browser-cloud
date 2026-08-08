/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ProxyProvider = {
    providerId: string;
    type: 'STATIC_HTTP' | 'STATIC_HTTP_CATALOG';
    endpoint: string;
    expectedExitIp: string;
    directFallbackAllowed: boolean;
    state: 'CONFIGURED' | 'CATALOG_CONFIGURED' | 'UNCONFIGURED';
};
