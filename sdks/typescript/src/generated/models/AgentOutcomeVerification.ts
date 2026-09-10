/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentOutcomeVerification = {
    verificationId: string | null;
    status: 'NOT_REQUIRED' | 'QUEUED' | 'IN_REVIEW' | 'VERIFIED' | 'NOT_VERIFIED' | 'FAILED';
    decision: 'VERIFIED' | 'NOT_VERIFIED';
    reasonCodes: Array<string>;
    evidenceHash: string | null;
    deploymentId: string | null;
    modelName: string | null;
    modelRevision: string | null;
    inputTokens: number | null;
    outputTokens: number | null;
    costMicros: number | null;
    latencyMs: number | null;
    failureCode: string | null;
    completedAt: string | null;
};
