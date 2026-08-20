/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { SessionIdentitySpecInput } from './SessionIdentitySpecInput.js';
export type CreateSessionIdentityChangeRequest = {
    expectedVersion: number;
    proposedSpec: SessionIdentitySpecInput;
    reason: string;
};
