/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { WorkspaceGroup } from './WorkspaceGroup.js';
import type { WorkspaceGroupSession } from './WorkspaceGroupSession.js';
export type WorkspaceGroupListResponse = {
    items: Array<WorkspaceGroup>;
    unassignedSessions: Array<WorkspaceGroupSession>;
    total: number;
};
