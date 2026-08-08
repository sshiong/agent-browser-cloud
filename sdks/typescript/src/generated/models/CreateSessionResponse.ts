/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ResourcePolicy } from './ResourcePolicy.js';
import type { SessionContext } from './SessionContext.js';
export type CreateSessionResponse = {
    sessionId: string;
    operationId?: string | null;
    state: string;
    resourcePolicy: ResourcePolicy;
    context: SessionContext;
};
