/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type TenantRouteMigration = {
    migrationId: string;
    tenantId: string;
    sourceRouteEpoch: number;
    targetRouteEpoch: number;
    sourceVirtualPartitions: number;
    targetVirtualPartitions: number;
    state: 'MIGRATING' | 'COMMITTED' | 'FAILED';
    totalSessions: number;
    migratedSessions: number;
    blockedSessions: number;
    requestedBy: string;
    requestId: string;
    failureCode?: string | null;
    createdAt: string;
    updatedAt: string;
    completedAt?: string | null;
};
