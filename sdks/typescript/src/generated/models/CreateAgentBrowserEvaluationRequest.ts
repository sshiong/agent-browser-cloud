/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBrowserEvaluationMode } from './AgentBrowserEvaluationMode.js';
export type CreateAgentBrowserEvaluationRequest = {
    /**
     * Intent evaluated by the existing risk and high-risk confirmation policy.
     */
    goal: string;
    mode: AgentBrowserEvaluationMode;
    /**
     * Sealed for Node delivery and never returned by this API.
     */
    expression: string;
    expectedStateCursor: string;
    awaitPromise?: boolean;
    timeoutMs?: number;
    maximumResultBytes?: number;
};
