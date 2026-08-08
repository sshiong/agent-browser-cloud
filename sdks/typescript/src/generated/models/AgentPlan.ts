/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentPlanStep } from './AgentPlanStep.js';
export type AgentPlan = {
    intentId: string;
    steps: Array<AgentPlanStep>;
    maxActions: number;
    replanBudget: number;
    expiresAt: string;
};
