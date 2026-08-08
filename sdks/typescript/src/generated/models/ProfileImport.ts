/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ProfileImport = {
    importId: string;
    operationId: string;
    profileId: string;
    profileName: string;
    runtimeBuildId: string;
    archiveSha256: string;
    archiveSizeBytes: number;
    state: 'REQUESTED' | 'UPLOADING' | 'VALIDATING' | 'COMMITTED' | 'FAILED';
    nodeId?: string | null;
    checkpointId: string;
    checkpointEpoch?: number | null;
    profileWriteEpoch?: number | null;
    coreSizeBytes?: number | null;
    checkpointFileCount?: number | null;
    errorCode?: string | null;
    requestId: string;
    createdAt: string;
    updatedAt: string;
    completedAt?: string | null;
};
