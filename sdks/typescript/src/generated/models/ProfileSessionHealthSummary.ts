/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ProfileSessionHealthSummary = {
    state: 'NOT_CHECKED' | 'HEALTHY' | 'REAUTH_REQUIRED' | 'DEGRADED' | 'STALE';
    siteCount: number;
    checkedAt?: string | null;
    freshUntil?: string | null;
};
