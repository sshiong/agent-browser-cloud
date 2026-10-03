/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { StableTargetRegion } from './StableTargetRegion.js';
import type { UnstableTargetRegion } from './UnstableTargetRegion.js';
/**
 * Bounded Node full-sample evidence, bound to this exact state, tab and document. This is not an action grant or a whole-page STABLE claim. Missing during rolling upgrades means unknown; aged or degraded states return unknown.
 */
export type RegionalStability = {
    evidenceFresh: boolean;
    /**
     * True only after at least 15 seconds of continuously sampled page change.
     */
    maxWaitReached: boolean;
    changingMillis: number;
    /**
     * False while any form, SPA write, payment/security, critical transaction, upload or download remains active. Unknown writes are not exempted.
     */
    transactionFree: boolean;
    stableRegions: Array<StableTargetRegion>;
    unstableRegions: Array<UnstableTargetRegion>;
};
