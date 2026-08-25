/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentBatchActionInput = {
    actionId: string;
    toolId: 'CLICK_TARGET' | 'DOUBLE_CLICK_TARGET' | 'RIGHT_CLICK_TARGET' | 'HOVER_TARGET' | 'CLEAR_TARGET' | 'CHECK_TARGET' | 'UNCHECK_TARGET' | 'TYPE_TEXT' | 'FILL' | 'PASTE_AGENT_CLIPBOARD' | 'SCROLL' | 'WAIT_FOR' | 'OPEN_TAB' | 'SWITCH_TAB' | 'CLOSE_TAB' | 'ACCEPT_DIALOG' | 'DISMISS_DIALOG' | 'PRESS_KEY' | 'SELECT_OPTION' | 'DRAG_TARGET' | 'DROP_TARGET' | 'SWIPE_TARGET' | 'MOUSE_MOVE' | 'MOUSE_DOWN' | 'MOUSE_UP' | 'MOUSE_WHEEL' | 'KEY_DOWN' | 'KEY_UP' | 'TOUCH_START' | 'TOUCH_MOVE' | 'TOUCH_END';
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
    dialogId: string | null;
    endTargetRef: string | null;
    endElementId: string | null;
    key: string | null;
    button: number | null;
    deltaX: number | null;
    deltaY: number | null;
    durationMs: number | null;
};
