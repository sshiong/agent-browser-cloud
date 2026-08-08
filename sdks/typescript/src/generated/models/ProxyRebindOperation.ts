/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ProxyRebindOperation = {
    workflowId: string;
    operationId: string;
    phase: 'CHECKPOINTING' | 'PLACING_TARGET' | 'RESTORING' | 'TARGET_CLEANUP' | 'STATE_RESYNC' | 'BUSINESS_VALIDATION' | 'BUSINESS_RECOVERY_ACTION' | 'COMPLETED' | 'DEGRADED' | 'FAILED';
    createdAt: string;
};
