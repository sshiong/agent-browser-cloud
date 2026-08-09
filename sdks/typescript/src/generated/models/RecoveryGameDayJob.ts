/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RecoveryGameDayJob = {
    gameDayId: string;
    scenarioCode: string;
    environment: 'TEST' | 'STAGING' | 'PRODUCTION';
    requiredWorkerCapabilities: Record<string, boolean>;
    state: 'QUEUED' | 'CLAIMED' | 'EXECUTING' | 'RECOVERY_REQUIRED' | 'RECOVERING' | 'ACKED' | 'COMMITTED' | 'FAILED' | 'ABORTED';
    currentStage: 'QUEUED' | 'PREPARING' | 'INJECTING' | 'FAULT_INJECTED' | 'OBSERVING' | 'RECOVERY_REQUIRED' | 'RECOVERING' | 'VALIDATING' | 'COMMITTED' | 'FAILED' | 'ABORTED';
    attempt: number;
    maximumAttempts: number;
    recoveryAttempt: number;
    maximumRecoveryAttempts: number;
    workerId: string | null;
    claimEpoch: number;
    availableAt: string;
    leaseExpiresAt: string | null;
    lastHeartbeatAt: string | null;
    abortDeadline: string;
    abortRequested: boolean;
    faultInjected: boolean;
    recoveryConfirmed: boolean | null;
    failureCode: string | null;
    resultHash: string | null;
    updatedAt: string;
};
