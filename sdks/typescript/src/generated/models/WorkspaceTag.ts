/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { WorkspaceTagSession } from './WorkspaceTagSession.js';
export type WorkspaceTag = {
    tagId: string;
    name: string;
    description?: string | null;
    color: string;
    sessions: Array<WorkspaceTagSession>;
    sessionCount: number;
    createdBy: string;
    createdAt: string;
    updatedAt: string;
};
