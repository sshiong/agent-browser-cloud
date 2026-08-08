/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { WorkspaceBatchState } from './WorkspaceBatchState.js';
import type { WorkspaceMetadataBatchAction } from './WorkspaceMetadataBatchAction.js';
import type { WorkspaceMetadataBatchOperationItem } from './WorkspaceMetadataBatchOperationItem.js';
import type { WorkspaceMetadataBatchSelector } from './WorkspaceMetadataBatchSelector.js';
import type { WorkspaceMetadataBatchTarget } from './WorkspaceMetadataBatchTarget.js';
export type WorkspaceMetadataBatchOperation = {
    batchOperationId: string;
    action: WorkspaceMetadataBatchAction;
    state: WorkspaceBatchState;
    selector: WorkspaceMetadataBatchSelector;
    target: WorkspaceMetadataBatchTarget;
    reason: string;
    total: number;
    accepted: number;
    executing: number;
    succeeded: number;
    failed: number;
    cancelled: number;
    cancellationRequested: boolean;
    items: Array<WorkspaceMetadataBatchOperationItem>;
    actorId: string;
    createdAt: string;
    updatedAt: string;
};
