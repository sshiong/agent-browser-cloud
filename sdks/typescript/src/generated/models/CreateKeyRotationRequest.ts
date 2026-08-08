/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type CreateKeyRotationRequest = {
    keyScope: 'NODE_MTLS' | 'RUNTIME_SIGNING' | 'PROFILE_KEK' | 'REMOTE_DESKTOP' | 'AGENT_CAPABILITY';
    oldKeyId: string;
    newKeyId: string;
    rotationTrigger: 'SCHEDULED' | 'PERSONNEL_CHANGE' | 'POLICY_CHANGE' | 'SUSPECTED_COMPROMISE' | 'TENANT_REQUEST';
    reason: string;
    overlapMinutes: number;
};
