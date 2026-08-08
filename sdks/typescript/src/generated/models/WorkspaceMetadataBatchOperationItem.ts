/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { WorkspaceBatchItemState } from './WorkspaceBatchItemState.js';
export type WorkspaceMetadataBatchOperationItem = {
    batchItemId: string;
    sessionId: string;
    ordinal: number;
    state: WorkspaceBatchItemState;
    failureCode?: string | null;
    attempt: number;
    createdAt: string;
    startedAt?: string | null;
    completedAt?: string | null;
};
