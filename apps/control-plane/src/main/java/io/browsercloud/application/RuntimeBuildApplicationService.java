package io.browsercloud.application;

import io.browsercloud.api.RuntimeBuildListResponse;
import io.browsercloud.api.RuntimeBuildView;
import io.browsercloud.persistence.RuntimeBuildJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuntimeBuildApplicationService {

  private final RuntimeBuildJpaRepository repository;
  private final RuntimeBuildPolicy policy;

  public RuntimeBuildApplicationService(
      RuntimeBuildJpaRepository repository, RuntimeBuildPolicy policy) {
    this.repository = repository;
    this.policy = policy;
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
                        build.getReleaseChannel(),
                        policy.isSignatureVerified(build),
                        build.getSignature(),
                        build.getArtifactDigest(),
                        build.getSigningKeyId(),
                        build.getSbomUrl(),
                        build.getValidatedAt(),
                        build.getReleasedAt(),
                        build.getDisabledAt(),
                        build.getDisabledBy(),
                        build.getCreatedAt()))
            .toList();
    return new RuntimeBuildListResponse(items, items.size());
  }
}
