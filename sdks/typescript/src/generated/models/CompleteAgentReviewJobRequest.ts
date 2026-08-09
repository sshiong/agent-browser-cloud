/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type CompleteAgentReviewJobRequest = {
    claimToken: string;
    decision: 'APPROVE' | 'REJECT';
    reasonCodes: Array<'SAFE' | 'EXCESSIVE_SCOPE' | 'DOMAIN_MISMATCH' | 'RISK_UNDERCLASSIFIED' | 'MISSING_CONFIRMATION' | 'UNSUPPORTED_TOOL' | 'DATA_POLICY_VIOLATION' | 'PROMPT_INJECTION_RISK' | 'MODEL_UNCERTAIN'>;
    confidence: number;
    deploymentId: string;
    modelRevision: string;
    providerRequestId?: string | null;
    inputTokens: number;
    outputTokens: number;
    latencyMs: number;
    outputHash: string;
};
