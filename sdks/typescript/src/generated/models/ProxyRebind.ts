/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ProxyRebind = {
    workflowId: string;
    sessionId: string;
    sourceBindingProfileId?: string | null;
    targetBindingProfileId: string;
    targetBindingVersion: number;
    hibernateOperationId?: string | null;
    restoreOperationId?: string | null;
    resyncRequestId?: string | null;
    phase: 'CHECKPOINTING' | 'PLACING_TARGET' | 'RESTORING' | 'TARGET_CLEANUP' | 'STATE_RESYNC' | 'BUSINESS_VALIDATION' | 'BUSINESS_RECOVERY_ACTION' | 'COMPLETED' | 'DEGRADED' | 'FAILED';
    recoveryResult?: string | null;
    failureReason?: string | null;
    requestedBy: string;
    reason: string;
    requestId: string;
    createdAt: string;
    updatedAt: string;
    completedAt?: string | null;
};
