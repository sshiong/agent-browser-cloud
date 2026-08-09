/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentExecutionJob = {
    jobId: string;
    taskId: string;
    protocolVersion: string;
    state: 'QUEUED' | 'CLAIMED' | 'EXECUTING' | 'WAITING' | 'COMMITTED' | 'FAILED';
    attempt: number;
    maximumAttempts: number;
    workerId: string | null;
    claimEpoch: number;
    leaseExpiresAt: string | null;
    availableAt: string;
    startedAt: string | null;
    completedAt: string | null;
    failureCode: string | null;
    updatedAt: string;
};
