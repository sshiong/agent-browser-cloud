/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { WorkspaceBatchAction } from './WorkspaceBatchAction.js';
import type { WorkspaceBatchSelector } from './WorkspaceBatchSelector.js';
export type CreateWorkspaceBatchOperationRequest = {
    action: WorkspaceBatchAction;
    selector: WorkspaceBatchSelector;
    reason?: string | null;
    confirmed: boolean;
};
