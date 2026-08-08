/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type SessionApplicationRebind = {
    operationId: string;
    sessionId: string;
    applicationId: string;
    contractId: string;
    previousContractVersion: number;
    targetContractVersion: number;
    state: 'COMMITTED';
    requestId: string;
    createdAt: string;
    completedAt: string;
};
