/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { MaximumReachedPolicy } from './MaximumReachedPolicy.js';
export type WorkspaceGroupRequest = {
    name: string;
    description?: string | null;
    color: string;
    defaultOnMaximumReached: MaximumReachedPolicy;
    defaultAllowMigration: boolean;
    defaultAllowHibernate: boolean;
};
