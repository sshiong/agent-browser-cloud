/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type BusinessRecoveryAction = {
    actionId: string;
    migrationId: string;
    attemptNumber: number;
    action: 'RELOAD' | 'NAVIGATE_HOME' | 'REOPEN_KNOWN_ROUTE' | 'REFRESH_SESSION' | 'RESTART_EXTENSION';
    targetUrl?: string | null;
    targetExtensionId?: string | null;
    baseStateVersion: number;
    resultingStateVersion?: number | null;
    state: 'REQUESTED' | 'EXECUTING' | 'ACKNOWLEDGED' | 'COMMITTED' | 'FAILED';
    errorCode?: string | null;
    createdAt: string;
    completedAt?: string | null;
};
