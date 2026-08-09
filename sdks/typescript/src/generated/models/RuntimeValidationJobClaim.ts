/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RuntimeValidation } from './RuntimeValidation.js';
export type RuntimeValidationJobClaim = {
    claimToken: string;
    validation: RuntimeValidation;
    leaseExpiresAt: string;
    claimEpoch: number;
};
