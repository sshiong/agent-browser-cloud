/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RecoveryGameDayEvent = {
    eventId: string;
    gameDayId: string;
    eventType: string;
    fromState: string | null;
    toState: string;
    stage: string;
    workerId: string | null;
    claimEpoch: number;
    attempt: number;
    reasonCode: string | null;
    occurredAt: string;
};
