package io.browsercloud.persistence;

import io.browsercloud.api.ResourcePolicyRequest;
import io.browsercloud.domain.resource.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "session_resource_policies")
public class SessionResourcePolicyEntity {
  @Id private String sessionId;

  @Column(nullable = false)
  private String tenantId;

  @Column(nullable = false)
  private String mode;

  @Column(nullable = false)
  private String executionEnvironment;

  @Column(nullable = false)
  private String minimumTemplate;

  @Column(nullable = false)
  private String resolvedTemplate;

  @Column(nullable = false)
  private int maximumCpuMillis;

  @Column(nullable = false)
  private int maximumMemoryMib;

  private BigDecimal maximumCostPerHour;

  @Column(nullable = false)
  private int scaleUpWindowSeconds;

  @Column(nullable = false)
  private int scaleDownWindowSeconds;

  @Column(nullable = false)
  private int adjustmentCooldownSeconds;

  @Column(nullable = false)
  private boolean allowMigration;

  @Column(nullable = false)
  private boolean allowHibernate;

  @Column(nullable = false)
  private boolean blockMigrationDuringHumanTakeover;

  @Column(nullable = false)
  private String onMaximumReached;

  @Column(nullable = false)
  private String status;

  private String statusReason;
  private Instant lastEvaluatedAt;
  private Instant lastAdjustedAt;
  private BigDecimal currentHourlyCost;
  private String costPricingVersion;
  private Instant lastCostEvaluatedAt;
  private Instant maximumMitigationAt;
  private String maximumMitigationOperationId;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @Version private long version;

  protected SessionResourcePolicyEntity() {}

  public static SessionResourcePolicyEntity create(
      String sessionId, String tenantId, ResourcePolicyRequest request, Instant now) {
    var entity = new SessionResourcePolicyEntity();
    entity.sessionId = sessionId;
    entity.tenantId = tenantId;
    entity.createdAt = now;
    entity.status = ResourcePolicyStatus.OBSERVING.name();
    entity.statusReason = "AWAITING_RUNTIME_TELEMETRY";
    entity.mode = ResourcePolicyMode.AUTO.name();
    entity.executionEnvironment = ExecutionEnvironment.SYSTEM_MANAGED.name();
    entity.minimumTemplate = "standard-v1";
    entity.resolvedTemplate = "standard-v1";
    entity.maximumCpuMillis = 4000;
    entity.maximumMemoryMib = 4096;
    entity.scaleUpWindowSeconds = 60;
    entity.scaleDownWindowSeconds = 1200;
    entity.adjustmentCooldownSeconds = 300;
    entity.allowMigration = true;
    entity.allowHibernate = true;
    entity.blockMigrationDuringHumanTakeover = true;
    entity.onMaximumReached = MaximumReachedPolicy.PAUSE_AGENT.name();
    entity.apply(request, now);
    return entity;
  }

  public void apply(ResourcePolicyRequest request, Instant now) {
    mode = ResourcePolicyMode.AUTO.name();
    if (request != null) {
      if (request.executionEnvironment() != null) {
        executionEnvironment = request.executionEnvironment().name();
      }
      if (request.minimumTemplate() != null && !request.minimumTemplate().isBlank()) {
        minimumTemplate = request.minimumTemplate();
      }
      if (request.maximumCpuMillis() != null) maximumCpuMillis = request.maximumCpuMillis();
      if (request.maximumMemoryMib() != null) maximumMemoryMib = request.maximumMemoryMib();
      if (request.maximumCostPerHour() != null) {
        maximumCostPerHour = BigDecimal.valueOf(request.maximumCostPerHour());
      }
      if (request.scaleUpWindowSeconds() != null) {
        scaleUpWindowSeconds = request.scaleUpWindowSeconds();
      }
      if (request.scaleDownWindowSeconds() != null) {
        scaleDownWindowSeconds = request.scaleDownWindowSeconds();
      }
      if (request.adjustmentCooldownSeconds() != null) {
        adjustmentCooldownSeconds = request.adjustmentCooldownSeconds();
      }
      if (request.allowMigration() != null) allowMigration = request.allowMigration();
      if (request.allowHibernate() != null) allowHibernate = request.allowHibernate();
      if (request.blockMigrationDuringHumanTakeover() != null) {
        blockMigrationDuringHumanTakeover = request.blockMigrationDuringHumanTakeover();
      }
      if (request.onMaximumReached() != null) {
        onMaximumReached = request.onMaximumReached().name();
      }
    }
    clearMaximumMitigation();
    updatedAt = now;
  }

  public void resolveTemplate(String template, Instant now) {
    resolvedTemplate = template;
    status = ResourcePolicyStatus.OBSERVING.name();
    statusReason = "PLACEMENT_RESOLVED_AWAITING_TELEMETRY";
    clearMaximumMitigation();
    updatedAt = now;
  }

  public void evaluate(ResourcePolicyStatus next, String reason, Instant now) {
    status = next.name();
    statusReason = reason;
    lastEvaluatedAt = now;
    updatedAt = now;
  }

  public void adjustmentCommitted(
      ResourcePolicyStatus next, String reason, String resolvedTemplate, Instant now) {
    status = next.name();
    statusReason = reason;
    this.resolvedTemplate = resolvedTemplate;
    lastEvaluatedAt = now;
    lastAdjustedAt = now;
    updatedAt = now;
  }

  public void recordCost(BigDecimal hourlyCost, String pricingVersion, Instant now) {
    if (hourlyCost == null || hourlyCost.signum() < 0) {
      throw new IllegalArgumentException("hourly cost must be non-negative");
    }
    if (pricingVersion == null || pricingVersion.isBlank()) {
      throw new IllegalArgumentException("pricing version is required");
    }
    currentHourlyCost = hourlyCost;
    costPricingVersion = pricingVersion;
    lastCostEvaluatedAt = now;
    updatedAt = now;
  }

  public void recordCostUnavailable(boolean failClosed, Instant now) {
    lastCostEvaluatedAt = now;
    if (failClosed) {
      status = ResourcePolicyStatus.CRITICAL.name();
      statusReason = "COST_EVALUATION_UNAVAILABLE_BROWSER_PRESERVED";
      lastEvaluatedAt = now;
    }
    updatedAt = now;
  }

  public void markMaximumMitigation(String operationId, Instant now) {
    if (operationId == null || operationId.isBlank()) {
      throw new IllegalArgumentException("maximum mitigation operation is required");
    }
    maximumMitigationAt = now;
    maximumMitigationOperationId = operationId;
    updatedAt = now;
  }

  public void clearMaximumMitigation() {
    maximumMitigationAt = null;
    maximumMitigationOperationId = null;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public ResourcePolicyMode mode() {
    return ResourcePolicyMode.valueOf(mode);
  }

  public ExecutionEnvironment executionEnvironment() {
    return ExecutionEnvironment.valueOf(executionEnvironment);
  }

  public String getMinimumTemplate() {
    return minimumTemplate;
  }

  public String getResolvedTemplate() {
    return resolvedTemplate;
  }

  public int getMaximumCpuMillis() {
    return maximumCpuMillis;
  }

  public int getMaximumMemoryMib() {
    return maximumMemoryMib;
  }

  public Double getMaximumCostPerHour() {
    return maximumCostPerHour == null ? null : maximumCostPerHour.doubleValue();
  }

  public int getScaleUpWindowSeconds() {
    return scaleUpWindowSeconds;
  }

  public int getScaleDownWindowSeconds() {
    return scaleDownWindowSeconds;
  }

  public int getAdjustmentCooldownSeconds() {
    return adjustmentCooldownSeconds;
  }

  public boolean isAllowMigration() {
    return allowMigration;
  }

  public boolean isAllowHibernate() {
    return allowHibernate;
  }

  public boolean isBlockMigrationDuringHumanTakeover() {
    return blockMigrationDuringHumanTakeover;
  }

  public MaximumReachedPolicy onMaximumReached() {
    return MaximumReachedPolicy.valueOf(onMaximumReached);
  }

  public ResourcePolicyStatus status() {
    return ResourcePolicyStatus.valueOf(status);
  }

  public String getStatusReason() {
    return statusReason;
  }

  public Instant getLastEvaluatedAt() {
    return lastEvaluatedAt;
  }

  public Instant getLastAdjustedAt() {
    return lastAdjustedAt;
  }

  public BigDecimal getCurrentHourlyCost() {
    return currentHourlyCost;
  }

  public String getCostPricingVersion() {
    return costPricingVersion;
  }

  public Instant getLastCostEvaluatedAt() {
    return lastCostEvaluatedAt;
  }

  public Instant getMaximumMitigationAt() {
    return maximumMitigationAt;
  }

  public String getMaximumMitigationOperationId() {
    return maximumMitigationOperationId;
  }
}
