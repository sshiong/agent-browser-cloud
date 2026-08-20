/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBatchActionRequest } from './AgentBatchActionRequest.js';
export type ExecuteAgentBrowserActionsRequest = {
    /**
     * User-authorized intent used by the existing risk and confirmation policy.
     */
    goal: string;
    expectedStateCursor: string;
    actions: Array<AgentBatchActionRequest>;
    stopOnError?: boolean;
};
