/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { TargetBounds } from './TargetBounds.js';
export type UnstableTargetRegion = {
    /**
     * Empty for page-wide unknown or region-budget markers.
     */
    elementId: string;
    bounds: (TargetBounds | null);
    reason: 'OUTSIDE_PROVEN_TARGET_REGIONS' | 'UNPROVEN_FRAME_CONTEXT' | 'TARGET_NOT_ACTIONABLE' | 'TARGET_WINDOW_INCOMPLETE' | 'TARGET_REGION_BUDGET';
};
