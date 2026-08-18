/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ChallengeAutomationRun = {
    runId: string;
    challengeEventId: string;
    state: 'CAPTURING' | 'ANALYZING' | 'EXECUTING' | 'COMPLETED' | 'EXHAUSTED' | 'ESCALATED' | 'FAILED';
    attemptCount: number;
    maximumAttempts: number;
    lastAction?: string | null;
    lastErrorCode?: string | null;
    updatedAt: string;
    completedAt?: string | null;
};
