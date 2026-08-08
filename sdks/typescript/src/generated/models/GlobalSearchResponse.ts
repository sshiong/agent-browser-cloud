/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { GlobalSearchResult } from './GlobalSearchResult.js';
export type GlobalSearchResponse = {
    query: string;
    items: Array<GlobalSearchResult>;
    limit: number;
    truncated: boolean;
};
