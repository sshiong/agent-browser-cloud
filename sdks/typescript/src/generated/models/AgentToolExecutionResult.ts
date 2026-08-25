/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentToolExecutionResult = {
    stepId: string;
    toolId: 'NAVIGATE' | 'GET_CURRENT_STATE' | 'CLICK_TARGET' | 'DOUBLE_CLICK_TARGET' | 'RIGHT_CLICK_TARGET' | 'HOVER_TARGET' | 'CLEAR_TARGET' | 'CHECK_TARGET' | 'UNCHECK_TARGET' | 'TYPE_TEXT' | 'FILL' | 'PASTE_AGENT_CLIPBOARD' | 'SCROLL' | 'WAIT_FOR' | 'OPEN_TAB' | 'SWITCH_TAB' | 'CLOSE_TAB' | 'ACCEPT_DIALOG' | 'DISMISS_DIALOG' | 'PRESS_KEY' | 'SELECT_OPTION' | 'DRAG_TARGET' | 'DROP_TARGET' | 'SWIPE_TARGET' | 'MOUSE_MOVE' | 'MOUSE_DOWN' | 'MOUSE_UP' | 'MOUSE_WHEEL' | 'KEY_DOWN' | 'KEY_UP' | 'TOUCH_START' | 'TOUCH_MOVE' | 'TOUCH_END' | 'EXECUTE_ACTIONS' | 'GET_URL' | 'GET_PAGE_SUMMARY' | 'REQUEST_HUMAN_TAKEOVER';
    status: 'VERIFIED' | 'WAITING_FOR_HUMAN' | 'ACCEPTED';
    resultHash: string;
    output: Record<string, any>;
    verification: string;
    completedAt: string;
};
