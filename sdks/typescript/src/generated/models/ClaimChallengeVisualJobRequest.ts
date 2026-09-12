/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ClaimChallengeVisualJobRequest = {
    protocolVersion: string;
    /**
     * Must include screenshot-ocr-actions-v1 and local-ocr-pii-gate-v1.
     */
    capabilities: Record<string, boolean>;
    deploymentId: string;
    modelRevision: string;
};
