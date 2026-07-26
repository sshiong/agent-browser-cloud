package io.browsercloud.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateKeyRotationRequest(
    @NotBlank
        @Pattern(
            regexp = "^(NODE_MTLS|RUNTIME_SIGNING|PROFILE_KEK|REMOTE_DESKTOP|AGENT_CAPABILITY)$")
        String keyScope,
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:/-]{3,200}$") String oldKeyId,
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:/-]{3,200}$") String newKeyId,
    @NotBlank
        @Pattern(
            regexp =
                "^(SCHEDULED|PERSONNEL_CHANGE|POLICY_CHANGE|SUSPECTED_COMPROMISE|TENANT_REQUEST)$")
        String rotationTrigger,
    @NotBlank @Size(min = 20, max = 500) String reason,
    @Min(0) @Max(1440) int overlapMinutes) {}
