/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type EnterpriseRegion = {
    regionId: string;
    role: 'PRIMARY' | 'SECONDARY' | 'DR';
    admissionState: 'OPEN' | 'CLOSED' | 'FAILOVER_READY';
    replicationLagSeconds: number;
    lastVerifiedAt: string;
    updatedBy: string;
};
