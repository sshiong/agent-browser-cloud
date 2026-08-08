/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentRiskClass } from './AgentRiskClass.js';
import type { AgentStepInput } from './AgentStepInput.js';
export type AgentPlanStep = {
    stepId: string;
    toolId: 'NAVIGATE' | 'GET_CURRENT_STATE' | 'CLICK_TARGET' | 'TYPE_TEXT' | 'SCROLL' | 'WAIT_FOR' | 'GET_URL' | 'GET_PAGE_SUMMARY' | 'REQUEST_HUMAN_TAKEOVER';
    riskClass: AgentRiskClass;
    targetUrl: string | null;
    input: (AgentStepInput | null);
    rationale: string;
    supportingSources: Array<string>;
    trustFloor: 'TRUSTED' | 'RESTRICTED' | 'UNTRUSTED';
    taintLabels: Array<string>;
    requiredConfirmation: boolean;
    strategy: 'SEMANTIC_DOM' | 'ACCESSIBILITY' | 'DESKTOP_INPUT' | 'VISION_DESKTOP' | 'HUMAN_ASSIST' | 'HUMAN_TAKEOVER';
    requiredStateQuality: string;
    verification: string;
    /**
     * Non-secret handle. The signed bearer token is never returned to the console.
     */
    capabilityTokenId: string;
};
