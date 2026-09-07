/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentRecoveryGuidance = {
    directive: 'RETRY' | 'REFRESH' | 'REPLAN' | 'WAIT' | 'HUMAN' | 'TERMINAL';
    reasonCode: string;
    /**
     * True when the control plane is already carrying out this recovery decision.
     */
    automatic: boolean;
};
