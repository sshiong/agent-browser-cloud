/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentInputSecret = {
    secretId: string;
    sessionId: string;
    purpose: 'USERNAME' | 'PASSWORD' | 'OTP';
    expiresAt: string;
    consumed: boolean;
};
