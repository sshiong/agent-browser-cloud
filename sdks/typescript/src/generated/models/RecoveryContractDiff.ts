/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RecoveryContractFieldChange } from './RecoveryContractFieldChange.js';
export type RecoveryContractDiff = {
    contractId: string;
    applicationId: string;
    fromVersion: number;
    toVersion: number;
    changes: Array<RecoveryContractFieldChange>;
    total: number;
};
