package io.browsercloud.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

public record RecordExtensionSampleRequest(
    @NotBlank @Pattern(regexp = "^node_[a-zA-Z0-9_-]{1,123}$") String nodeId,
    @Min(0) @Max(100_000) int cpuMillis,
    @Min(0) @Max(1_048_576) int memoryMib,
    boolean cgroupPsiBurst,
    @Min(0) @Max(1_000) int sampleCpuMillis,
    @NotNull Instant observedAt) {}
