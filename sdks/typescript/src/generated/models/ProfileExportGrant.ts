/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ProfileExportPurpose } from './ProfileExportPurpose.js';
export type ProfileExportGrant = {
    grantId: string;
    profileId: string;
    checkpointId: string;
    checkpointEpoch: number;
    purpose: ProfileExportPurpose;
    state: 'ISSUED' | 'REDEEMING' | 'REDEEMED' | 'FAILED';
    expiresAt: string;
    createdAt: string;
    redeemedAt?: string | null;
    archiveSha256?: string | null;
    archiveSizeBytes?: number | null;
    errorCode?: string | null;
    requestId?: string | null;
};
