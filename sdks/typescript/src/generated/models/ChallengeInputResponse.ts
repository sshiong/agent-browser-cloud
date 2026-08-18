/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ChallengeInputResponse = {
    intentId: string;
    challengeEventId: string;
    sessionId: string;
    taskId: string;
    purpose: string;
    state: 'EXECUTING' | 'COMMITTED' | 'FAILED' | 'EXPIRED';
    maximumAttempts: number;
    operationId: string;
    expiresAt: string;
    createdAt: string;
    completedAt: string | null;
    errorCode: string | null;
};
