/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type WaitForAgentBrowserRequest = {
    goal: string;
    expectedStateCursor: string;
    waitCondition: 'STATE_CHANGED' | 'STATE_STABLE' | 'TARGET_PRESENT';
    /**
     * Required when waitCondition is TARGET_PRESENT.
     */
    targetRef?: string;
    timeoutMs: number;
};
