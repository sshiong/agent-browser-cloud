/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type HumanAssistIntent = {
    intentId: string;
    challengeEventId: string;
    sessionId: string;
    userId: string;
    contextEpoch: number;
    stateVersion: number;
    targetRevision: number;
    allowedTargetRef: string;
    allowedActionCount: number;
    consumedCount: number;
    authorizationEventId: string;
    operationId: string | null;
    requestId: string;
    state: 'AUTHORIZED' | 'EXECUTING' | 'COMMITTED' | 'FAILED' | 'EXPIRED';
    expiresAt: string;
    createdAt: string;
    consumedAt: string | null;
    completedAt: string | null;
    errorCode: string | null;
};
