/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ReleaseFreeze = {
    tenantId: string;
    enabled: boolean;
    phase: 'OPEN' | 'FROZEN' | 'RECOVERING';
    frozen: boolean;
    currentBurnRate: number;
    freezeBurnRateThreshold: number;
    recoveryBurnRateThreshold: number;
    evaluationWindowMinutes: number;
    recoveryStableMinutes: number;
    reasonCode: string;
    stableSince: string | null;
    frozenAt: string | null;
    clearedAt: string | null;
    evaluatedAt: string;
    version: number;
};
