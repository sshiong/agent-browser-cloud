/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type UpsertRetentionPolicyRequest = {
    dataClass: 'AUDIT' | 'AGENT_EXECUTION' | 'PROFILE_CHECKPOINT' | 'REMOTE_DESKTOP_RECORDING' | 'SECURE_DEBUG';
    retentionDays: number;
    legalHold: boolean;
    residencyRegion: string;
};
