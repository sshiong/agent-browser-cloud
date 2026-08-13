/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ProfileWarmTierStatus = {
    state: 'AWAITING_FIRST_SYNC' | 'LIVE';
    nodeId?: string | null;
    profileWriteEpoch?: number | null;
    journalSequence?: number | null;
    transactionBarrier?: string | null;
    changedFileCount?: number | null;
    deletedFileCount?: number | null;
    reusedChunkCount?: number | null;
    uploadedBytes?: number | null;
    deferredGroupCount?: number | null;
    manifestSha256?: string | null;
    committedAt?: string | null;
};
