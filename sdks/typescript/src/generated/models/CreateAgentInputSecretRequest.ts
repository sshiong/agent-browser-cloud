/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type CreateAgentInputSecretRequest = {
    purpose: 'USERNAME' | 'PASSWORD' | 'OTP';
    value: string;
    /**
     * Optional expiry no later than 30 minutes from creation.
     */
    expiresAt?: string;
};
