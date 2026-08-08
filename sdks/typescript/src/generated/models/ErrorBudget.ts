/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ErrorBudget = {
    tenantId: string;
    availabilityTarget: number;
    latencyP95TargetMs: number;
    windowMinutes: number;
    allowedUnavailableSeconds: number;
    consumedUnavailableSeconds: number;
    remainingUnavailableSeconds: number;
    burnRatio: number;
    state: 'HEALTHY' | 'EXHAUSTED';
    windowStartedAt: string;
    calculatedAt: string;
};
