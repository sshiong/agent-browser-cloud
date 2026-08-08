/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type CreateBreakGlassRequest = {
    ticketId: string;
    reason: string;
    resourceType: 'SESSION' | 'PROFILE' | 'AUDIT' | 'RUNTIME' | 'TENANT';
    resourceId: string;
    requestedScope: 'READ_SENSITIVE_STATE' | 'SECURE_DEBUG' | 'AUDIT_EXPORT' | 'INCIDENT_RESPONSE';
    durationMinutes: number;
};
