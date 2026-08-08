/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ProxyAllocation } from './ProxyAllocation.js';
import type { ProxyProvider } from './ProxyProvider.js';
export type ProxyOverview = {
    provider: ProxyProvider;
    allocations: Array<ProxyAllocation>;
    total: number;
};
