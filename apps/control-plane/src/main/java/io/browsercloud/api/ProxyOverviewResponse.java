package io.browsercloud.api;

import java.util.List;

public record ProxyOverviewResponse(
    ProxyProviderView provider, List<ProxyAllocationView> allocations, int total) {}
