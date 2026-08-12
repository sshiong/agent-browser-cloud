/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ResourceAdjustment = {
    operationId: string;
    state: 'REQUESTED' | 'EXECUTING' | 'ACKNOWLEDGED' | 'COMMITTED' | 'FAILED';
    reason: string;
    failureCode?: string | null;
    oldResources: Record<string, any>;
    requestedResources: Record<string, any>;
    requestedAt: string;
    executingAt?: string | null;
    acknowledgedAt?: string | null;
    completedAt?: string | null;
    updatedAt: string;
};
