/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RuntimeValidationMatrixCellRequest } from './RuntimeValidationMatrixCellRequest.js';
export type StartRuntimeValidationMatrixRequest = {
    buildId: string;
    suiteVersion: string;
    replayDatasetId: string;
    persona: string;
    cells: Array<RuntimeValidationMatrixCellRequest>;
};
