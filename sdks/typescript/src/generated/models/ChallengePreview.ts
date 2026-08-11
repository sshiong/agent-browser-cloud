/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ChallengeEvent } from './ChallengeEvent.js';
import type { ChallengeRegion } from './ChallengeRegion.js';
export type ChallengePreview = {
    challenge: ChallengeEvent;
    /**
     * Empty when the current State cannot be authorized.
     */
    previewHash: string;
    highlight: (ChallengeRegion | null);
    fresh: boolean;
    canAuthorize: boolean;
    blockingReason: string | null;
    previewedAt: string;
};
