/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type UpsertRegionRequest = {
    role: 'PRIMARY' | 'SECONDARY' | 'DR';
    admissionState: 'OPEN' | 'CLOSED' | 'FAILOVER_READY';
    replicationLagSeconds: number;
};
