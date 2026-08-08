/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentActionRequest = {
    toolId: 'CLICK_TARGET' | 'TYPE_TEXT' | 'SCROLL' | 'WAIT_FOR' | 'REQUEST_HUMAN_TAKEOVER';
    targetRef?: string;
    targetRevision?: number;
    value?: string;
    dataClass?: 'PUBLIC' | 'PII';
    scrollDeltaY?: number;
    waitCondition?: 'STATE_CHANGED' | 'STATE_STABLE' | 'TARGET_PRESENT';
    timeoutMs?: number;
};
