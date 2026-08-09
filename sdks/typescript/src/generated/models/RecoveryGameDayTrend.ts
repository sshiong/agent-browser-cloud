/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RecoveryGameDayTrend = {
    scenario: string;
    environment: 'TEST' | 'STAGING' | 'PRODUCTION';
    totalRuns: number;
    passedRuns: number;
    failedRuns: number;
    abortedRuns: number;
    recoveryUnknownRuns: number;
    passRatePercent: number;
    p95RtoSeconds: number | null;
    p95RpoSeconds: number | null;
    openTicketCount: number;
    latestRunAt: string;
};
