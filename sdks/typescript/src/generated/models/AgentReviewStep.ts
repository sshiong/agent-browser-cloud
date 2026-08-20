/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentRiskClass } from './AgentRiskClass.js';
export type AgentReviewStep = {
    stepId: string;
    toolId: 'NAVIGATE' | 'GET_CURRENT_STATE' | 'CLICK_TARGET' | 'DOUBLE_CLICK_TARGET' | 'RIGHT_CLICK_TARGET' | 'HOVER_TARGET' | 'CLEAR_TARGET' | 'CHECK_TARGET' | 'UNCHECK_TARGET' | 'TYPE_TEXT' | 'FILL' | 'PASTE_AGENT_CLIPBOARD' | 'SCROLL' | 'WAIT_FOR' | 'EXECUTE_ACTIONS' | 'GET_URL' | 'GET_PAGE_SUMMARY' | 'REQUEST_HUMAN_TAKEOVER';
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
