/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RecoveryGameDayRemediation = {
    ticketId: string;
    gameDayId: string;
    scenario: string;
    environment: 'TEST' | 'STAGING' | 'PRODUCTION';
    severity: 'P1' | 'P2' | 'P3';
    state: 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';
    reasonCode: string;
    summary: string;
    ownerId: string | null;
    resolution: string | null;
    createdBy: string;
    createdAt: string;
    updatedBy: string;
    updatedAt: string;
    resolvedAt: string | null;
};
