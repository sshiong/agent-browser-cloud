package io.browsercloud.api;

import java.util.List;

public record AgentTaskListResponse(List<AgentTaskView> items, int total, int limit, int offset) {}
