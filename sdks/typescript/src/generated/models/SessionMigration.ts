/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BusinessRecoveryAction } from './BusinessRecoveryAction.js';
export type SessionMigration = {
    migrationId: string;
    sessionId: string;
    sourceNodeId: string;
    targetNodeId?: string | null;
    sourceContextEpoch: number;
    targetContextEpoch?: number | null;
    checkpointId?: string | null;
    hibernateOperationId?: string | null;
    restoreOperationId?: string | null;
    targetCleanupOperationId?: string | null;
    targetAttempt: number;
    maximumTargetAttempts: number;
    failedTargetNodeIds: Array<string>;
    lastTargetFailureReason?: string | null;
    resyncRequestId?: string | null;
    phase: 'CHECKPOINTING' | 'PLACING_TARGET' | 'RESTORING' | 'TARGET_CLEANUP' | 'STATE_RESYNC' | 'BUSINESS_VALIDATION' | 'BUSINESS_RECOVERY_ACTION' | 'COMPLETED' | 'DEGRADED' | 'FAILED';
    recoveryResult?: string | null;
    failureReason?: string | null;
    autoRecoveryAttempts: number;
    autoRecoveryMaximum: number;
    latestRecoveryAction?: (BusinessRecoveryAction | null);
    createdAt: string;
    updatedAt: string;
    completedAt?: string | null;
};
