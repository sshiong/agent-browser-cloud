/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentReviewStep } from './AgentReviewStep.js';
import type { AgentRiskClass } from './AgentRiskClass.js';
export type AgentReviewPayload = {
    taskId: string;
    /**
     * Prompt-security sanitized goal; credential, email and phone patterns are redacted.
     */
    goal: string;
    riskClass: AgentRiskClass;
    allowedDomains: Array<string>;
    maximumActions: number;
    replanBudget: number;
    steps: Array<AgentReviewStep>;
    planHash: string;
    dataPolicy: string;
};
