/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { GlobalSearchResponse } from '../models/GlobalSearchResponse.js';
import type { SearchResourceType } from '../models/SearchResourceType.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class SearchService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Search authorized workspace resources
     * Performs a bounded, tenant-scoped search across Sessions, Profiles, Groups and Tags. Runtime and Browser Node results follow their existing resource-level visibility; Node matches are omitted for non-admin identities. Secret values and browser content are never indexed or returned.
     *
     * @returns GlobalSearchResponse Ranked authorized search results.
     * @throws ApiError
     */
    public globalSearch({
        q,
        types,
        limit = 24,
    }: {
        q: string,
        types?: Array<SearchResourceType>,
        limit?: number,
    }): CancelablePromise<GlobalSearchResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/search',
            query: {
                'q': q,
                'types': types,
                'limit': limit,
            },
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
}
