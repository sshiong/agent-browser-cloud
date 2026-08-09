/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RecoveryGameDay } from './RecoveryGameDay.js';
export type RecoveryGameDayJobClaim = {
    claimToken: string;
    gameDay: RecoveryGameDay;
    leaseExpiresAt: string;
    claimEpoch: number;
    recoveryOnly: boolean;
};
