/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { EnvironmentPrimaryView } from './EnvironmentPrimaryView.js';
import type { EnvironmentSavedViewScope } from './EnvironmentSavedViewScope.js';
import type { EnvironmentSavedViewTagMatch } from './EnvironmentSavedViewTagMatch.js';
import type { SessionState } from './SessionState.js';
export type CreateEnvironmentSavedViewRequest = {
    name: string;
    scope: EnvironmentSavedViewScope;
    primaryView: EnvironmentPrimaryView;
    sessionState?: (SessionState | null);
    searchQuery?: string;
    groupId?: (string | null);
    tagIds?: Array<string>;
    tagMatch?: EnvironmentSavedViewTagMatch;
    showRuntimeColumn: boolean;
    showContextColumn: boolean;
    showOperationColumn: boolean;
};
