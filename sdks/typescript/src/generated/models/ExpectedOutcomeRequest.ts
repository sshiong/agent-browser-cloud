/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ExpectedOutcomeType } from './ExpectedOutcomeType.js';
export type ExpectedOutcomeRequest = {
    outcomeId: string;
    type: ExpectedOutcomeType;
    /**
     * Required only for TARGET_* expectations.
     */
    role?: string | null;
    /**
     * Exact URL, title or accessible target name; never persisted or returned.
     */
    matchValue: string;
};
