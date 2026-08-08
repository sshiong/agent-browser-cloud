/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentConfirmation } from './AgentConfirmation.js';
import type { AgentHumanHandoff } from './AgentHumanHandoff.js';
import type { AgentPlan } from './AgentPlan.js';
import type { AgentPolicy } from './AgentPolicy.js';
import type { AgentRiskClass } from './AgentRiskClass.js';
import type { AgentStepExecution } from './AgentStepExecution.js';
import type { AgentToolExecutionResult } from './AgentToolExecutionResult.js';
import type { PromptSecurityEvent } from './PromptSecurityEvent.js';
export type AgentTask = {
    taskId: string;
    sessionId: string;
    /**
     * Data-minimized goal; secrets, email and phone patterns are redacted.
     */
    goal: string;
    state: 'PLANNED' | 'AWAITING_CONFIRMATION' | 'BLOCKED' | 'RUNNING' | 'WAITING_FOR_HUMAN' | 'COMPLETED' | 'FAILED';
    riskClass: AgentRiskClass;
    intentDecision: 'ALLOWED' | 'CONFIRM_REQUIRED' | 'FORBIDDEN';
    blockedReason: string | null;
    /**
     * Session policy bound when this task plan was created. Old Control Planes may omit it during rolling upgrades.
     */
    agentPolicy?: AgentPolicy;
    currentStep: number;
    totalSteps: number;
    replanCount: number;
    stepExecution: AgentStepExecution;
    confirmation: AgentConfirmation;
    humanHandoff: AgentHumanHandoff;
    allowedDomains: Array<string>;
    plan: AgentPlan;
    operationId: string | null;
    executionResults: Array<AgentToolExecutionResult>;
    lastError: string | null;
    securityEvents: Array<PromptSecurityEvent>;
    createdAt: string;
    updatedAt: string;
};
