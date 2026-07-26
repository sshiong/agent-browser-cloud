package io.browsercloud.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record RegisterBrowserNodeRequest(
    @NotBlank @Pattern(regexp = "^[a-z0-9-]{1,32}$") String region,
    @NotBlank @Size(max = 255) String grpcTarget,
    @Min(100) @Max(1_000_000) int certifiedCpuMillis,
    @Min(128) @Max(4_194_304) int certifiedMemoryMib,
    @Min(32) @Max(1_000_000) int certifiedPidCount,
    @Min(0) @Max(1024) int certifiedGpuSlots,
    @Min(0) @Max(10_000) int certifiedMediaSlots,
    @Min(10) @Max(40) int safetyMarginPercent,
    @Min(1) @Max(100_000) int maxSessions,
    boolean supportsDesktop,
    boolean supportsGpu,
    boolean supportsMedia,
    boolean supportsNativeOs,
    boolean isolationCapable,
    @Size(max = 32)
        Map<@NotBlank @Size(max = 64) String, @NotBlank @Size(max = 128) String> labels) {}
