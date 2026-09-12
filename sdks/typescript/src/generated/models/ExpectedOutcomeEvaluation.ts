/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ExpectedOutcomeType } from './ExpectedOutcomeType.js';
export type ExpectedOutcomeEvaluation = {
    outcomeId: string;
    type: ExpectedOutcomeType;
    status: 'SATISFIED' | 'NOT_SATISFIED' | 'INDETERMINATE';
    reasonCode: string;
    observedValueHash: string | null;
};
