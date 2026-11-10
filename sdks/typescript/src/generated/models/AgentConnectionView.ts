/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentConnectionView = {
    connectionId: string;
    name: string;
    subject: string;
    maximumEnvironments: number;
    maximumRunning: number;
    expiresAt: string;
    enabled: boolean;
    allowedTools: Array<'quota' | 'list' | 'get' | 'create' | 'start' | 'stop' | 'delete' | 'operation'>;
};
