/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { WorkspaceBatchItemState } from './WorkspaceBatchItemState.js';
export type WorkspaceBatchOperationItem = {
    batchItemId: string;
    sessionId: string;
    ordinal: number;
    commandId: string;
    state: WorkspaceBatchItemState;
    /**
     * Child Operation ID or Migration ledger ID for MIGRATE actions.
     */
    childOperationId?: string | null;
    failureCode?: string | null;
    createdAt: string;
    startedAt?: string | null;
    completedAt?: string | null;
};
