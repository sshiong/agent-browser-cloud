/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentExecutionMemoryEvent = {
    sequence: number;
    eventType: 'STEP_RESULT' | 'STEP_FAILURE' | 'REPLAN';
    planIntentId: string | null;
    stepOrdinal: number | null;
    stepId: string | null;
    toolId: 'NAVIGATE' | 'GET_CURRENT_STATE' | 'CLICK_TARGET' | 'DOUBLE_CLICK_TARGET' | 'RIGHT_CLICK_TARGET' | 'HOVER_TARGET' | 'CLEAR_TARGET' | 'CHECK_TARGET' | 'UNCHECK_TARGET' | 'TYPE_TEXT' | 'FILL' | 'PASTE_AGENT_CLIPBOARD' | 'SCROLL' | 'WAIT_FOR' | 'OPEN_TAB' | 'SWITCH_TAB' | 'CLOSE_TAB' | 'ACCEPT_DIALOG' | 'DISMISS_DIALOG' | 'PRESS_KEY' | 'SELECT_OPTION' | 'DRAG_TARGET' | 'DROP_TARGET' | 'SWIPE_TARGET' | 'MOUSE_MOVE' | 'MOUSE_DOWN' | 'MOUSE_UP' | 'MOUSE_WHEEL' | 'KEY_DOWN' | 'KEY_UP' | 'TOUCH_START' | 'TOUCH_MOVE' | 'TOUCH_END' | 'EXECUTE_ACTIONS' | 'GET_URL' | 'GET_PAGE_SUMMARY' | 'REQUEST_HUMAN_TAKEOVER';
    /**
     * Stable step fingerprint excluding plaintext, sealed secrets, capability material and ephemeral IDs.
     */
    semanticKey: string | null;
    status: string | null;
    resultHash: string | null;
    verification: string | null;
    reasonCode: string | null;
    stateVersion: number | null;
    createdAt: string;
};
