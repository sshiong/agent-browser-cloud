/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
/**
 * Immutable Session-scoped Agent capability and budget policy. DISABLED rejects every task; RESTRICTED permits observation and human handoff only; BALANCED and INTERACTIVE permit the bounded tool set with progressively larger action and replan budgets.
 *
 */
export type AgentPolicy = 'DISABLED' | 'RESTRICTED' | 'BALANCED' | 'INTERACTIVE';
