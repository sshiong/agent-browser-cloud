/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentRiskClass } from './AgentRiskClass.js';
export type AgentReviewStep = {
    stepId: string;
    toolId: 'NAVIGATE' | 'GET_CURRENT_STATE' | 'CLICK_TARGET' | 'DOUBLE_CLICK_TARGET' | 'RIGHT_CLICK_TARGET' | 'HOVER_TARGET' | 'CLEAR_TARGET' | 'CHECK_TARGET' | 'UNCHECK_TARGET' | 'TYPE_TEXT' | 'FILL' | 'PASTE_AGENT_CLIPBOARD' | 'SCROLL' | 'WAIT_FOR' | 'OPEN_TAB' | 'SWITCH_TAB' | 'CLOSE_TAB' | 'ACCEPT_DIALOG' | 'DISMISS_DIALOG' | 'PRESS_KEY' | 'SELECT_OPTION' | 'DRAG_TARGET' | 'DROP_TARGET' | 'SWIPE_TARGET' | 'MOUSE_MOVE' | 'MOUSE_DOWN' | 'MOUSE_UP' | 'MOUSE_WHEEL' | 'KEY_DOWN' | 'KEY_UP' | 'TOUCH_START' | 'TOUCH_MOVE' | 'TOUCH_END' | 'EXECUTE_ACTIONS' | 'GET_URL' | 'GET_PAGE_SUMMARY' | 'REQUEST_HUMAN_TAKEOVER';
    riskClass: AgentRiskClass;
    /**
     * Origin only; URL paths, queries, fragments and user-info are excluded.
     */
    targetOrigin: string | null;
    targetRefHash: string | null;
    dataClass: 'PUBLIC' | 'PII';
    payloadLength: number | null;
    batchActionCount: number;
    /**
     * Hash of minimized ordered batch metadata; no plaintext or sealed payload is shared.
     */
    batchActionHash: string | null;
    requiredConfirmation: boolean;
    strategy: 'SEMANTIC_DOM' | 'ACCESSIBILITY' | 'DESKTOP_INPUT' | 'VISION_DESKTOP' | 'HUMAN_ASSIST' | 'HUMAN_TAKEOVER';
    requiredStateQuality: string;
    verification: string;
};
