package io.browsercloud.api;

import java.util.List;

public record BrowserNodeListResponse(List<BrowserNodeView> items, int total) {}
