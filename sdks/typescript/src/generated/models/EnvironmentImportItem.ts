/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { EnvironmentImportExecutionState } from './EnvironmentImportExecutionState.js';
import type { EnvironmentImportSpec } from './EnvironmentImportSpec.js';
import type { EnvironmentImportValidationState } from './EnvironmentImportValidationState.js';
export type EnvironmentImportItem = {
    itemId: string;
    itemIndex: number;
    specification: EnvironmentImportSpec;
    validationState: EnvironmentImportValidationState;
    validationErrors: Array<string>;
    executionState: EnvironmentImportExecutionState;
    sessionId?: string | null;
    operationId?: string | null;
    requestId?: string | null;
    updatedAt: string;
};
