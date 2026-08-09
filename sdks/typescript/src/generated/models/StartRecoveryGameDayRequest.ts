/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RecoveryGameDayBlastRadius } from './RecoveryGameDayBlastRadius.js';
export type StartRecoveryGameDayRequest = {
    scenario: string;
    sourceRegion: string;
    targetRegion: string;
    rtoTargetSeconds: number;
    rpoTargetSeconds: number;
    executionMode?: 'MANUAL' | 'AUTO';
    environment?: 'TEST' | 'STAGING' | 'PRODUCTION';
    blastRadius?: RecoveryGameDayBlastRadius;
    maximumDurationSeconds?: number;
    approvalRequestId?: string;
    requiredWorkerCapabilities?: Record<string, boolean>;
    maximumAttempts?: number;
};
