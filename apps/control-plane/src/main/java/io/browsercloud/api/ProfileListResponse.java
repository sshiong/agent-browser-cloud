package io.browsercloud.api;

import java.util.List;

public record ProfileListResponse(List<ProfileView> items, int total) {}
