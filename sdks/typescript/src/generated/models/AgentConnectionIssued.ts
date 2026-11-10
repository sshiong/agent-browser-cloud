/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentConnectionView } from './AgentConnectionView.js';
export type AgentConnectionIssued = {
    connection: AgentConnectionView;
    /**
     * Returned once; store securely. Never included in list or audit.
     */
    accessToken: string;
};
