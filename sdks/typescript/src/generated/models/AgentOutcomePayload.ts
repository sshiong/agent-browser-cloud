/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentRiskClass } from './AgentRiskClass.js';
import type { OutcomeExecutionEvidence } from './OutcomeExecutionEvidence.js';
import type { OutcomeStateEvidence } from './OutcomeStateEvidence.js';
export type AgentOutcomePayload = {
    taskId: string;
    goal: string;
    riskClass: AgentRiskClass;
    allowedDomains: Array<string>;
    executionEvidence: Array<OutcomeExecutionEvidence>;
    finalState: OutcomeStateEvidence;
    evidenceHash: string;
    dataPolicy: string;
};
