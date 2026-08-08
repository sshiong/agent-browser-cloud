/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentActionRequest } from './AgentActionRequest.js';
import type { AgentInstructionSource } from './AgentInstructionSource.js';
export type CreateAgentTaskRequest = {
    goal: string;
    startUrl?: string;
    allowedDomains: Array<string>;
    maxActions?: number;
    replanBudget?: number;
    contextSources?: Array<AgentInstructionSource>;
    actions?: Array<AgentActionRequest>;
};
