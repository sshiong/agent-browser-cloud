/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type EnterpriseOverviewStreamChange = {
    sequence: number;
    changeType: 'RUNTIME_VALIDATION' | 'COST_RATE' | 'MEDIA_QUOTA' | 'ERROR_BUDGET' | 'RELEASE_FREEZE' | 'SLA_EXCLUSION' | 'RETENTION' | 'LICENSE' | 'REGION' | 'RECOVERY_GAMEDAY' | 'COMPLIANCE';
    occurredAt: string;
    replayed: boolean;
};
