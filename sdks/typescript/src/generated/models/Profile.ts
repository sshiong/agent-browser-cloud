/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
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
    state: 'ACTIVE';
    createdAt: string;
    updatedAt: string;
    lastCheckpointAt?: string | null;
};
