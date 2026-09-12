/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentActionRequest } from './AgentActionRequest.js';
import type { AgentInstructionSource } from './AgentInstructionSource.js';
import type { ExpectedOutcomeRequest } from './ExpectedOutcomeRequest.js';
export type CreateAgentTaskRequest = {
    goal: string;
    startUrl?: string;
    allowedDomains: Array<string>;
    maxActions?: number;
    replanBudget?: number;
    contextSources?: Array<AgentInstructionSource>;
    actions?: Array<AgentActionRequest>;
    /**
     * Structured final-state expectations. Raw matchValue is normalized and only its SHA-256 is persisted.
     */
    expectedOutcomes?: Array<ExpectedOutcomeRequest>;
};
