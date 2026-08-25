/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBatchActionRequest } from './AgentBatchActionRequest.js';
export type AgentActionRequest = {
    toolId: 'CLICK_TARGET' | 'DOUBLE_CLICK_TARGET' | 'RIGHT_CLICK_TARGET' | 'HOVER_TARGET' | 'CLEAR_TARGET' | 'CHECK_TARGET' | 'UNCHECK_TARGET' | 'TYPE_TEXT' | 'FILL' | 'PASTE_AGENT_CLIPBOARD' | 'SCROLL' | 'WAIT_FOR' | 'OPEN_TAB' | 'SWITCH_TAB' | 'CLOSE_TAB' | 'ACCEPT_DIALOG' | 'DISMISS_DIALOG' | 'PRESS_KEY' | 'SELECT_OPTION' | 'DRAG_TARGET' | 'DROP_TARGET' | 'SWIPE_TARGET' | 'MOUSE_MOVE' | 'MOUSE_DOWN' | 'MOUSE_UP' | 'MOUSE_WHEEL' | 'KEY_DOWN' | 'KEY_UP' | 'TOUCH_START' | 'TOUCH_MOVE' | 'TOUCH_END' | 'EXECUTE_ACTIONS' | 'REQUEST_HUMAN_TAKEOVER';
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
    tabId?: string;
    tabUrl?: string;
    dialogId?: string;
    endTargetRef?: string;
    /**
     * Bounded key name or one printable character; never accepts arbitrary CDP key payloads.
     */
    key?: string;
    button?: number;
    deltaX?: number;
    deltaY?: number;
    durationMs?: number;
    actions?: Array<AgentBatchActionRequest>;
    stopOnError?: boolean;
};
