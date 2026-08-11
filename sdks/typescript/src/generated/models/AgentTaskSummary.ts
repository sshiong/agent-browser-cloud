/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentTaskSummary = {
    taskId: string;
    sessionId: string;
    goal: string;
    state: 'PLANNED' | 'QUEUED' | 'AWAITING_REVIEW' | 'AWAITING_CONFIRMATION' | 'BLOCKED' | 'RUNNING' | 'WAITING_FOR_HUMAN' | 'PAUSED_BY_RESOURCE_POLICY' | 'COMPLETED' | 'FAILED';
    riskClass: 'R0_READ_ONLY' | 'R1_LOW_RISK_CHANGE' | 'R2_DATA_CHANGE' | 'R3_ACCOUNT_CHANGE' | 'R4_FINANCIAL' | 'R5_SECURITY';
    intentDecision: 'ALLOWED' | 'CONFIRM_REQUIRED' | 'FORBIDDEN';
    blockedReason: string | null;
    agentPolicy: 'DISABLED' | 'RESTRICTED' | 'BALANCED' | 'INTERACTIVE';
    currentStep: number;
    totalSteps: number;
    securityEventCount: number;
    /**
     * Transient command arbitration projection. Older Control Planes may omit it during rolling upgrades.
     */
    executionWaitReason?: 'HUMAN_INPUT_PRIORITY';
    /**
     * First instant of the current arbitration wait. Older Control Planes may omit it during rolling upgrades.
     */
    executionWaitSince?: string | null;
    createdAt: string;
    updatedAt: string;
};
