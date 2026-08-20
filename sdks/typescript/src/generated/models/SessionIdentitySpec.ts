/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { SessionIdentitySpecInput } from './SessionIdentitySpecInput.js';
export type SessionIdentitySpec = {
    sessionId: string;
    version: number;
    specHash: string;
    locked: boolean;
    spec: SessionIdentitySpecInput;
    lockedAt: string;
    updatedAt: string;
};
