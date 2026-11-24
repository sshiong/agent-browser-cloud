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
    /**
     * DIRECT dispatches eligible low-risk actions through the Control Plane without queueing the plan reviewer or execution worker. Required approvals and configured outcome verification still apply.
     */
    executionMode?: 'MANAGED' | 'DIRECT';
    /**
     * Navigate the active tab in DIRECT mode. Send an empty actions array and take a fresh snapshot before acting.
     */
    navigateUrl?: string;
};
