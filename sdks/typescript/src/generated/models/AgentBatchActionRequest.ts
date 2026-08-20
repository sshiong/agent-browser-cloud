/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentBatchActionRequest = {
    toolId: 'CLICK_TARGET' | 'DOUBLE_CLICK_TARGET' | 'RIGHT_CLICK_TARGET' | 'HOVER_TARGET' | 'CLEAR_TARGET' | 'CHECK_TARGET' | 'UNCHECK_TARGET' | 'TYPE_TEXT' | 'FILL' | 'PASTE_AGENT_CLIPBOARD' | 'SCROLL' | 'WAIT_FOR' | 'OPEN_TAB' | 'SWITCH_TAB' | 'CLOSE_TAB';
    targetRef?: string;
    targetRevision?: number;
    value?: string;
    secretId?: string;
    dataClass?: 'PUBLIC' | 'PII' | 'CREDENTIAL' | 'OTP';
    scrollDeltaY?: number;
    waitCondition?: 'STATE_CHANGED' | 'STATE_STABLE' | 'TARGET_PRESENT';
    timeoutMs?: number;
    tabId?: string;
    tabUrl?: string;
};
