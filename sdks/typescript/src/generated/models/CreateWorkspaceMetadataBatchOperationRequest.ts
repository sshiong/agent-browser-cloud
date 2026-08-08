/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { WorkspaceMetadataBatchAction } from './WorkspaceMetadataBatchAction.js';
import type { WorkspaceMetadataBatchSelector } from './WorkspaceMetadataBatchSelector.js';
import type { WorkspaceMetadataBatchTarget } from './WorkspaceMetadataBatchTarget.js';
export type CreateWorkspaceMetadataBatchOperationRequest = {
    action: WorkspaceMetadataBatchAction;
    selector: WorkspaceMetadataBatchSelector;
    target: WorkspaceMetadataBatchTarget;
    reason: string;
    confirmed: boolean;
};
