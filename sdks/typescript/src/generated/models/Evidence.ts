/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type Evidence = {
    evidenceId: string;
    evidenceKind: 'AGENT_ACTION_SUCCESS' | 'AGENT_ACTION_FAILURE' | 'AGENT_NAVIGATION_SUCCESS' | 'AGENT_NAVIGATION_FAILURE' | 'OBSERVER_MANUAL';
    taskId: string;
    stepId: string;
    commandId: string;
    mandatory: boolean;
    result: 'COMMITTED' | 'FAILED';
    contentSha256?: string | null;
    contentBytes: number;
    capturedAt: string;
    errorCode?: string | null;
    redactionState: 'LEGACY_UNVERIFIED' | 'MASKED' | 'NOT_REQUIRED' | 'FAILED_CLOSED';
    redactedRegionCount: number;
};
