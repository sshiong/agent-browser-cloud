package io.browsercloud.application;

import io.browsercloud.api.RuntimeBuildListResponse;
import io.browsercloud.api.RuntimeBuildView;
import io.browsercloud.persistence.RuntimeBuildJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuntimeBuildApplicationService {

  private final RuntimeBuildJpaRepository repository;

  public RuntimeBuildApplicationService(RuntimeBuildJpaRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public RuntimeBuildListResponse list() {
    var items =
        repository.findAllByOrderByCreatedAtDesc().stream()
            .map(
                build ->
                    new RuntimeBuildView(
                        build.getBuildId(),
                        build.getEngine(),
                        build.getVersion(),
                        build.getPlatform(),
                        build.getSecurityTier(),
                        build.getRegressionStatus(),
                        build.getSignature() != null && !build.getSignature().isBlank(),
                        build.getSignature(),
                        build.getSbomUrl(),
                        build.getValidatedAt(),
                        build.getReleasedAt(),
                        build.getCreatedAt()))
            .toList();
    return new RuntimeBuildListResponse(items, items.size());
  }
}
