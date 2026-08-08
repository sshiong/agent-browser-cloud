/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type SessionApplicationBinding = {
    sessionId: string;
    applicationId: string;
    contractId: string;
    contractVersion: number;
    latestContractVersion: number;
    latestApprovalState: 'DRAFT' | 'REQUESTED' | 'APPROVED' | 'REJECTED';
    currentContractEnabled: boolean;
    upgradeAvailable: boolean;
    boundAt: string;
};
