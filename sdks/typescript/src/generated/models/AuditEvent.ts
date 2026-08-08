/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AuditEvent = {
    eventId: string;
    sequenceNo: number;
    sessionId?: string | null;
    eventType: string;
    actorType: string;
    actorId?: string | null;
    resourceType?: string | null;
    resourceId?: string | null;
    action: string;
    result: string;
    details: Record<string, any>;
    previousEventHash?: string | null;
    eventHash: string;
    requestId?: string | null;
    retentionUntil: string;
    legalHold: boolean;
    createdAt: string;
};
