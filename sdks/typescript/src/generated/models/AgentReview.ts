/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentReview = {
    reviewId: string | null;
    status: 'NOT_REQUIRED' | 'PENDING' | 'QUEUED' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'FAILED';
    decision: 'APPROVE' | 'REJECT';
    reasonCodes: Array<string>;
    planHash: string | null;
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
