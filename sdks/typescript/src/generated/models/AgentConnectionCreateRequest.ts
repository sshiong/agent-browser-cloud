/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentConnectionCreateRequest = {
    name: string;
    maximumEnvironments: number;
    maximumRunning: number;
    expiresAt: string;
    allowedTools?: Array<'quota' | 'list' | 'get' | 'create' | 'start' | 'stop' | 'delete' | 'operation'>;
};
