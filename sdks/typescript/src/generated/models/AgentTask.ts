/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentConfirmation } from './AgentConfirmation.js';
import type { AgentExecutionWait } from './AgentExecutionWait.js';
import type { AgentHumanHandoff } from './AgentHumanHandoff.js';
import type { AgentPlan } from './AgentPlan.js';
import type { AgentPolicy } from './AgentPolicy.js';
import type { AgentReview } from './AgentReview.js';
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
    state: 'PLANNED' | 'QUEUED' | 'AWAITING_REVIEW' | 'AWAITING_CONFIRMATION' | 'BLOCKED' | 'RUNNING' | 'WAITING_FOR_HUMAN' | 'PAUSED_BY_RESOURCE_POLICY' | 'COMPLETED' | 'FAILED';
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
    /**
     * Transient command arbitration projection. Older Control Planes may omit it during rolling upgrades.
     */
    executionWait?: AgentExecutionWait;
    confirmation: AgentConfirmation;
    humanHandoff: AgentHumanHandoff;
    /**
     * Reviewer evidence. Older Control Planes may omit it during rolling upgrades.
     */
    review?: AgentReview;
    allowedDomains: Array<string>;
    plan: AgentPlan;
    operationId: string | null;
    executionResults: Array<AgentToolExecutionResult>;
    lastError: string | null;
    securityEvents: Array<PromptSecurityEvent>;
    createdAt: string;
    updatedAt: string;
};
