/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { TargetBounds } from './TargetBounds.js';
export type OpaqueFrame = {
    /**
     * Stable opaque-boundary reference for the active tab and outer iframe path; never an action target.
     */
    frameRef: string;
    parentFrameId: string;
    /**
     * Scheme and authority only. Paths, query strings, fragments, credentials, and iframe content are never exposed.
     */
    origin: string | null;
    bounds: (TargetBounds | null);
    boundaryReason: 'CROSS_ORIGIN' | 'SANDBOXED' | 'INACCESSIBLE';
    visible: boolean;
    inViewport: boolean;
    occluded: boolean;
    visibilityReason: 'HIDDEN_ATTRIBUTE' | 'ARIA_HIDDEN' | 'DISPLAY_NONE' | 'VISIBILITY_HIDDEN' | 'OPACITY_ZERO' | 'POINTER_EVENTS_NONE' | 'COLLAPSED' | 'ZERO_SIZE' | 'OUTSIDE_VIEWPORT' | 'OCCLUDED';
    /**
     * The existing state/tab/region-fenced redacted screenshot path may observe the rectangle. Direct Agent input is forbidden because no semantic target can be revalidated; interaction requires Human Handoff.
     */
    interactionStrategy: 'BOUNDED_VISION_THEN_HUMAN_HANDOFF';
};
