/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type CreateDeletionReceiptRequest = {
    dataClass: 'AUDIT' | 'AGENT_EXECUTION' | 'PROFILE_CHECKPOINT' | 'REMOTE_DESKTOP_RECORDING' | 'SECURE_DEBUG';
    objectId: string;
    contentDigest: string;
};
