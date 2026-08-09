/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ReviewerModelDeployment } from './ReviewerModelDeployment.js';
export type AgentReviewJob = {
    jobId: string;
    reviewId: string;
    taskId: string;
    protocolVersion: string;
    state: 'QUEUED' | 'CLAIMED' | 'EXECUTING' | 'APPROVED' | 'REJECTED' | 'FAILED';
    attempt: number;
    maximumAttempts: number;
    workerId: string | null;
    claimEpoch: number;
    leaseExpiresAt: string | null;
    availableAt: string;
    deployment: ReviewerModelDeployment;
    decision: 'APPROVE' | 'REJECT';
    reasonCodes: Array<string>;
    confidence: number | null;
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
