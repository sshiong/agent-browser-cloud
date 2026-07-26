package io.browsercloud.application;

import io.browsercloud.persistence.RuntimeBuildJpaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Prevents unvalidated or unsigned Runtime artifacts from reaching Browser Nodes. */
@Service
public class RuntimeBuildPolicy {

  private final RuntimeBuildJpaRepository repository;
  private final boolean production;

  public RuntimeBuildPolicy(
      RuntimeBuildJpaRepository repository, @Value("${app.environment:local}") String environment) {
    this.repository = repository;
    this.production = "production".equalsIgnoreCase(environment);
  }

  public void requireApproved(String buildId) {
    var build =
        repository
            .findById(buildId)
            .orElseThrow(() -> new RuntimeBuildRejectedException("BUILD_NOT_REGISTERED"));
    if (!"STABLE".equals(build.getRegressionStatus())
        || build.getValidatedAt() == null
        || build.getReleasedAt() == null) {
      throw new RuntimeBuildRejectedException("BUILD_NOT_VALIDATED");
    }
    if (build.getSignature() == null
        || build.getSignature().isBlank()
        || build.getSbomUrl() == null
        || build.getSbomUrl().isBlank()) {
      throw new RuntimeBuildRejectedException("SUPPLY_CHAIN_EVIDENCE_MISSING");
    }
    if (production
        && (!build.getSignature().matches("^sha256:[a-f0-9]{64}$")
            || !(build.getSbomUrl().startsWith("https://")
                || build.getSbomUrl().startsWith("oci://")))) {
      throw new RuntimeBuildRejectedException("PRODUCTION_PROVENANCE_INVALID");
    }
  }

  public static final class RuntimeBuildRejectedException extends RuntimeException {
    public RuntimeBuildRejectedException(String reason) {
      super(reason);
    }
  }
}
