/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type OperationView = {
    operationId: string;
    ownerType: 'AGENT' | 'HUMAN' | 'SYSTEM';
    actorId: string | null;
    mode: string;
    priority: number;
    coordinatorTerm: number;
    contextEpoch: number;
    operationEpoch: number;
    workflowId: string | null;
    cancellable: boolean;
    preemptible: boolean;
    phase: string;
    state: string;
    allowedCapabilities: Array<string>;
    deadline: string;
};
