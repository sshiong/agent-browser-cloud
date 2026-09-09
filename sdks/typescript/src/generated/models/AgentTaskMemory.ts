/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentExecutionMemoryEvent } from './AgentExecutionMemoryEvent.js';
export type AgentTaskMemory = {
    /**
     * Highest task-local memory sequence included in this projection.
     */
    revision: number;
    executionHistory: Array<AgentExecutionMemoryEvent>;
};
