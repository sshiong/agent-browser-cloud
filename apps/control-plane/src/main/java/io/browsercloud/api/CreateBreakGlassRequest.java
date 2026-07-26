package io.browsercloud.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateBreakGlassRequest(
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9._/-]{3,128}$") String ticketId,
    @NotBlank @Size(min = 20, max = 500) String reason,
    @NotBlank @Pattern(regexp = "^(SESSION|PROFILE|AUDIT|RUNTIME|TENANT)$") String resourceType,
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String resourceId,
    @NotBlank
        @Pattern(regexp = "^(READ_SENSITIVE_STATE|SECURE_DEBUG|AUDIT_EXPORT|INCIDENT_RESPONSE)$")
        String requestedScope,
    @Min(5) @Max(60) int durationMinutes) {}
