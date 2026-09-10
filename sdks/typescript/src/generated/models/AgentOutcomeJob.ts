/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { OutcomeModelDeployment } from './OutcomeModelDeployment.js';
export type AgentOutcomeJob = {
    jobId: string;
    verificationId: string;
    taskId: string;
    protocolVersion: string;
    state: 'QUEUED' | 'CLAIMED' | 'EXECUTING' | 'VERIFIED' | 'NOT_VERIFIED' | 'FAILED';
    attempt: number;
    maximumAttempts: number;
    workerId: string | null;
    claimEpoch: number;
    leaseExpiresAt: string | null;
    availableAt: string;
    deployment: OutcomeModelDeployment;
    decision: 'VERIFIED' | 'NOT_VERIFIED';
    reasonCodes: Array<string>;
    confidence: number | null;
    evidenceHash: string;
    inputHash: string;
    outputHash: string | null;
    providerRequestId: string | null;
    inputTokens: number | null;
    outputTokens: number | null;
    costMicros: number | null;
    latencyMs: number | null;
    startedAt: string | null;
    completedAt: string | null;
    failureCode: string | null;
    updatedAt: string;
};
