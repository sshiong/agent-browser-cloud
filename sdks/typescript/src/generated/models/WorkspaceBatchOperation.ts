/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { WorkspaceBatchAction } from './WorkspaceBatchAction.js';
import type { WorkspaceBatchOperationItem } from './WorkspaceBatchOperationItem.js';
import type { WorkspaceBatchSelector } from './WorkspaceBatchSelector.js';
import type { WorkspaceBatchState } from './WorkspaceBatchState.js';
export type WorkspaceBatchOperation = {
    batchOperationId: string;
    action: WorkspaceBatchAction;
    state: WorkspaceBatchState;
    selector: WorkspaceBatchSelector;
    reason?: string | null;
    total: number;
    accepted: number;
    executing: number;
    succeeded: number;
    failed: number;
    cancelled: number;
    cancellationRequested: boolean;
    items: Array<WorkspaceBatchOperationItem>;
    actorId: string;
    createdAt: string;
    updatedAt: string;
};
