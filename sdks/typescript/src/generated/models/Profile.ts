/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ProfileSessionHealthSummary } from './ProfileSessionHealthSummary.js';
export type Profile = {
    profileId: string;
    tenantId: string;
    name: string;
    description?: string | null;
    latestCheckpointId?: string | null;
    latestCheckpointEpoch?: number | null;
    profileWriteEpoch: number;
    coreSizeBytes: number;
    checkpointFileCount: number;
    restoreStatus: 'EMPTY' | 'TECHNICAL_READY';
    sessionHealth: ProfileSessionHealthSummary;
    state: 'ACTIVE';
    createdAt: string;
    updatedAt: string;
    lastCheckpointAt?: string | null;
};
