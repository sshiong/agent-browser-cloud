/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBrowserEvaluationMode } from './AgentBrowserEvaluationMode.js';
export type AgentBrowserEvaluation = {
    evaluationId: string;
    sessionId: string;
    mode: AgentBrowserEvaluationMode;
    state: 'EXECUTING' | 'COMMITTED' | 'FAILED';
    expectedStateCursor: string;
    stateCursorAfter?: string | null;
    activeTabId: string;
    activeTabIdAfter?: string | null;
    expressionSha256: string;
    expressionBytes: number;
    awaitPromise: boolean;
    timeoutMs: number;
    maximumResultBytes: number;
    resultType?: string | null;
    /**
     * Any bounded JSON value after recursive sensitive-key redaction; null while executing or failed.
     */
    result?: any;
    resultBytes?: number | null;
    redactedValueCount?: number | null;
    exceptionClass?: string | null;
    exceptionMessage?: string | null;
    errorCode?: string | null;
    durationMs?: number | null;
    requestId: string;
    createdAt: string;
    updatedAt: string;
    completedAt?: string | null;
};
