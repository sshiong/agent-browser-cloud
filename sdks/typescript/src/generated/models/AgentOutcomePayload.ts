/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentRiskClass } from './AgentRiskClass.js';
import type { ExpectedOutcomeDefinition } from './ExpectedOutcomeDefinition.js';
import type { ExpectedOutcomeEvaluation } from './ExpectedOutcomeEvaluation.js';
import type { OutcomeExecutionEvidence } from './OutcomeExecutionEvidence.js';
import type { OutcomeStateEvidence } from './OutcomeStateEvidence.js';
export type AgentOutcomePayload = {
    taskId: string;
    goal: string;
    riskClass: AgentRiskClass;
    allowedDomains: Array<string>;
    expectedOutcomes: Array<ExpectedOutcomeDefinition>;
    expectedOutcomeEvaluations: Array<ExpectedOutcomeEvaluation>;
    executionEvidence: Array<OutcomeExecutionEvidence>;
    finalState: OutcomeStateEvidence;
    evidenceHash: string;
    dataPolicy: string;
};
