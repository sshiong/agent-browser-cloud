/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentBatchActionInput = {
    actionId: string;
    toolId: 'CLICK_TARGET' | 'DOUBLE_CLICK_TARGET' | 'RIGHT_CLICK_TARGET' | 'HOVER_TARGET' | 'CLEAR_TARGET' | 'CHECK_TARGET' | 'UNCHECK_TARGET' | 'TYPE_TEXT' | 'FILL' | 'PASTE_AGENT_CLIPBOARD' | 'SCROLL' | 'WAIT_FOR' | 'OPEN_TAB' | 'SWITCH_TAB' | 'CLOSE_TAB';
    targetRef: string | null;
    /**
     * Stable structured identity used to rebind this primitive after earlier actions advance targetRevision.
     */
    elementId: string | null;
    targetRevision: number | null;
    payloadHash: string | null;
    payloadLength: number | null;
    dataClass: string | null;
    scrollDeltaY: number | null;
    waitCondition: string | null;
    timeoutMs: number | null;
    sensitiveTargetAuthorized: boolean;
    maximumAttempts: number;
    tabId: string | null;
    tabUrl: string | null;
};
