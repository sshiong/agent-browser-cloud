/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { TargetBounds } from './TargetBounds.js';
export type InteractiveTarget = {
    targetRef: string;
    /**
     * Stable structured element ID; actions remain fenced by stateCursor/targetRevision.
     */
    elementId: string;
    role: string;
    name?: string | null;
    /**
     * Always null for password, OTP, payment and other sensitive controls.
     */
    value?: string | null;
    controlType?: string | null;
    bounds?: (TargetBounds | null);
    enabled: boolean;
    visible: boolean;
    sensitive: boolean;
    focused: boolean;
    checked?: boolean | null;
    selected?: boolean | null;
    interactive: boolean;
    frameId: string;
    inViewport: boolean;
    occluded: boolean;
    visibilityReason?: 'HIDDEN_ATTRIBUTE' | 'ARIA_HIDDEN' | 'DISPLAY_NONE' | 'VISIBILITY_HIDDEN' | 'OPACITY_ZERO' | 'POINTER_EVENTS_NONE' | 'COLLAPSED' | 'ZERO_SIZE' | 'OUTSIDE_VIEWPORT' | 'OCCLUDED' | 'LEGACY_VISIBILITY_UNKNOWN';
};
