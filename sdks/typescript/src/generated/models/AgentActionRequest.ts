/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentActionRequest = {
    toolId: 'CLICK_TARGET' | 'TYPE_TEXT' | 'SCROLL' | 'WAIT_FOR' | 'REQUEST_HUMAN_TAKEOVER';
    targetRef?: string;
    targetRevision?: number;
    value?: string;
    /**
     * One-time encrypted input reference. AUTONOMOUS mode and CREDENTIAL or OTP dataClass are required.
     */
    secretId?: string;
    dataClass?: 'PUBLIC' | 'PII' | 'CREDENTIAL' | 'OTP';
    scrollDeltaY?: number;
    waitCondition?: 'STATE_CHANGED' | 'STATE_STABLE' | 'TARGET_PRESENT';
    timeoutMs?: number;
};
