/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ProfileSessionHealthSummary } from './ProfileSessionHealthSummary.js';
import type { ProfileSiteSessionHealth } from './ProfileSiteSessionHealth.js';
export type ProfileSiteSessionHealthListResponse = {
    summary: ProfileSessionHealthSummary;
    items: Array<ProfileSiteSessionHealth>;
    total: number;
};
