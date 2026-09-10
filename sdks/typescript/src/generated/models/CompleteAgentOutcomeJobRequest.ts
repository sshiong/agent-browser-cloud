/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type CompleteAgentOutcomeJobRequest = {
    claimToken: string;
    decision: 'VERIFIED' | 'NOT_VERIFIED';
    reasonCodes: Array<'GOAL_SATISFIED' | 'GOAL_NOT_SATISFIED' | 'BUSINESS_ERROR_VISIBLE' | 'EXPECTED_STATE_MISSING' | 'WRONG_TARGET_OUTCOME' | 'STATE_STALE' | 'STATE_INCOMPLETE' | 'INSUFFICIENT_EVIDENCE' | 'MODEL_UNCERTAIN'>;
    confidence: number;
    deploymentId: string;
    modelRevision: string;
    providerRequestId?: string | null;
    inputTokens: number;
    outputTokens: number;
    latencyMs: number;
    outputHash: string;
};
