/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { TargetBounds } from './TargetBounds.js';
export type StableTargetRegion = {
    elementId: string;
    /**
     * Exact current target bounds; finite, positive size and nonnegative origin.
     */
    bounds: TargetBounds;
    quietMillis: number;
    consecutiveSamples: number;
};
