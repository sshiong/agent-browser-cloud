package io.browsercloud.api;

import java.util.List;

public record ExtensionProfileListResponse(List<ExtensionProfileView> items, int total) {}
