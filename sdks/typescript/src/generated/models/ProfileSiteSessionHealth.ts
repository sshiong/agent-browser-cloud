/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ProfileSiteSessionHealth = {
    healthId: string;
    profileId: string;
    siteOrigin: string;
    applicationId?: string | null;
    state: 'HEALTHY' | 'REAUTH_REQUIRED' | 'DEGRADED' | 'STALE';
    reasonCode: string;
    sourceSessionId?: string | null;
    contextEpoch: number;
    stateVersion: number;
    checkedAt: string;
    freshUntil: string;
    authenticatedAt?: string | null;
    reauthRequiredAt?: string | null;
};
