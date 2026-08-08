/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { MaximumReachedPolicy } from './MaximumReachedPolicy.js';
import type { WorkspaceGroupSession } from './WorkspaceGroupSession.js';
export type WorkspaceGroup = {
    groupId: string;
    name: string;
    description?: string | null;
    color: string;
    defaultOnMaximumReached: MaximumReachedPolicy;
    defaultAllowMigration: boolean;
    defaultAllowHibernate: boolean;
    sessions: Array<WorkspaceGroupSession>;
    sessionCount: number;
    createdBy: string;
    createdAt: string;
    updatedAt: string;
};
