/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type TenantRoute = {
    tenantId: string;
    state: 'STABLE' | 'MIGRATING';
    activeVirtualPartitions: number;
    activeRouteEpoch: number;
    pendingVirtualPartitions?: number | null;
    pendingRouteEpoch?: number | null;
    activeMigrationId?: string | null;
    version: number;
    updatedAt: string;
};
