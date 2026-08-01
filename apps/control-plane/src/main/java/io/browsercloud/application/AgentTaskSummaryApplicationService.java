package io.browsercloud.application;

import io.browsercloud.api.AgentTaskSummaryListResponse;
import io.browsercloud.persistence.AgentTaskSummaryQueryRepository;
import org.springframework.stereotype.Service;

@Service
public class AgentTaskSummaryApplicationService {

  private final AgentTaskSummaryQueryRepository repository;

  public AgentTaskSummaryApplicationService(AgentTaskSummaryQueryRepository repository) {
    this.repository = repository;
  }

  public AgentTaskSummaryListResponse list(String tenantId, int limit, String cursor) {
    return repository.list(tenantId, limit, cursor);
  }
}
