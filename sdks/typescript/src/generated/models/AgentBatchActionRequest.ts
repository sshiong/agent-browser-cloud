/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentBatchActionRequest = {
    toolId: 'CLICK_TARGET' | 'DOUBLE_CLICK_TARGET' | 'RIGHT_CLICK_TARGET' | 'HOVER_TARGET' | 'CLEAR_TARGET' | 'CHECK_TARGET' | 'UNCHECK_TARGET' | 'TYPE_TEXT' | 'FILL' | 'PASTE_AGENT_CLIPBOARD' | 'SCROLL' | 'WAIT_FOR' | 'OPEN_TAB' | 'SWITCH_TAB' | 'CLOSE_TAB' | 'ACCEPT_DIALOG' | 'DISMISS_DIALOG' | 'PRESS_KEY' | 'SELECT_OPTION' | 'DRAG_TARGET' | 'DROP_TARGET' | 'SWIPE_TARGET' | 'MOUSE_MOVE' | 'MOUSE_DOWN' | 'MOUSE_UP' | 'MOUSE_WHEEL' | 'KEY_DOWN' | 'KEY_UP' | 'TOUCH_START' | 'TOUCH_MOVE' | 'TOUCH_END';
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
    dialogId?: string;
    endTargetRef?: string;
    key?: string;
    button?: number;
    deltaX?: number;
    deltaY?: number;
    durationMs?: number;
};
