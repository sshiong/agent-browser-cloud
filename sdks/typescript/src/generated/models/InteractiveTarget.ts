/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { TargetBounds } from './TargetBounds.js';
export type InteractiveTarget = {
    targetRef: string;
    role: string;
    name?: string | null;
    bounds?: (TargetBounds | null);
    enabled: boolean;
    visible: boolean;
};
