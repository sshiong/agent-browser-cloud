/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type UpsertRetentionPolicyRequest = {
    dataClass: 'AUDIT' | 'AGENT_EXECUTION' | 'PROFILE_CHECKPOINT' | 'REMOTE_DESKTOP_RECORDING' | 'SECURE_DEBUG';
    retentionDays: number;
    legalHold: boolean;
    /**
     * Recording-only cloud protection. Omission preserves existing configuration, initially false; saving desired policy is not an applied cloud proof.
     */
    cloudHoldRequired?: boolean;
    residencyRegion: string;
};
