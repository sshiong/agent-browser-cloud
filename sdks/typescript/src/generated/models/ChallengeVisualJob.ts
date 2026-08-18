/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ChallengeVisualAction } from './ChallengeVisualAction.js';
export type ChallengeVisualJob = {
    jobId: string;
    runId: string;
    challengeEventId: string;
    state: 'CAPTURING' | 'READY' | 'CLAIMED' | 'RUNNING' | 'EXECUTING' | 'COMPLETED' | 'FAILED' | 'ESCALATED';
    attemptNumber: number;
    maximumAttempts: number;
    workerId?: string | null;
    claimEpoch: number;
    leaseExpiresAt?: string | null;
    decision?: 'ACT' | 'ESCALATE';
    actions: Array<ChallengeVisualAction>;
    confidence?: number | null;
    failureCode?: string | null;
    updatedAt: string;
};
