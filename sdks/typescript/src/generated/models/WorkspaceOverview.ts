/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { WorkspaceAgentSummary } from './WorkspaceAgentSummary.js';
import type { WorkspaceBrowserNodeSummary } from './WorkspaceBrowserNodeSummary.js';
import type { WorkspaceCostSummary } from './WorkspaceCostSummary.js';
import type { WorkspaceOperationSummary } from './WorkspaceOperationSummary.js';
import type { WorkspaceProxySummary } from './WorkspaceProxySummary.js';
import type { WorkspaceSecuritySummary } from './WorkspaceSecuritySummary.js';
import type { WorkspaceSessionSummary } from './WorkspaceSessionSummary.js';
export type WorkspaceOverview = {
    sessions: WorkspaceSessionSummary;
    operations: WorkspaceOperationSummary;
    browserNodes: WorkspaceBrowserNodeSummary;
    proxies: WorkspaceProxySummary;
    agents: WorkspaceAgentSummary;
    cost: WorkspaceCostSummary;
    security: WorkspaceSecuritySummary;
    cursor: number;
    generatedAt: string;
};
