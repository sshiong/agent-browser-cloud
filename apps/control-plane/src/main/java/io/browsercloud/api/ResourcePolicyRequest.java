package io.browsercloud.api;

import io.browsercloud.domain.resource.ExecutionEnvironment;
import io.browsercloud.domain.resource.MaximumReachedPolicy;
import io.browsercloud.domain.resource.ResourcePolicyMode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * User policy declaration. Internal resource classes and templates are never required from users.
 */
public record ResourcePolicyRequest(
    @NotNull ResourcePolicyMode mode,
    MaximumReachedPolicy onMaximumReached,
    Boolean allowMigration,
    Boolean allowHibernate,
    Boolean blockMigrationDuringHumanTakeover,
    ExecutionEnvironment executionEnvironment,
    @Pattern(regexp = "^(standard-v1|interactive-v1|heavy-v1|native-standard-v1)$")
        String minimumTemplate,
    @Min(500) @Max(32000) Integer maximumCpuMillis,
    @Min(512) @Max(131072) Integer maximumMemoryMib,
    @DecimalMin("0.0") Double maximumCostPerHour,
    @Min(30) @Max(900) Integer scaleUpWindowSeconds,
    @Min(300) @Max(86400) Integer scaleDownWindowSeconds,
    @Min(60) @Max(3600) Integer adjustmentCooldownSeconds) {}
