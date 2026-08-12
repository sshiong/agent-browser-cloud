/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RemoteDesktopParticipant } from './RemoteDesktopParticipant.js';
export type RemoteDesktopParticipantHistoryPage = {
    items: Array<RemoteDesktopParticipant>;
    total: number;
    limit: number;
    nextCursor: string | null;
    hasMore: boolean;
};
