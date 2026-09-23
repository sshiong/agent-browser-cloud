/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CloneProfileMode } from './CloneProfileMode.js';
import type { CreateSessionResponse } from './CreateSessionResponse.js';
export type CloneEnvironmentResponse = {
    sourceSessionId: string;
    profileMode: CloneProfileMode;
    targetProfileId: string;
    session: CreateSessionResponse;
};
