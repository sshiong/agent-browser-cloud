/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentTaskSummary } from './AgentTaskSummary.js';
import type { AgentTaskSummaryMetrics } from './AgentTaskSummaryMetrics.js';
export type AgentTaskSummaryListResponse = {
    items: Array<AgentTaskSummary>;
    metrics: AgentTaskSummaryMetrics;
    total: number;
    limit: number;
    nextCursor: string | null;
    hasMore: boolean;
};
