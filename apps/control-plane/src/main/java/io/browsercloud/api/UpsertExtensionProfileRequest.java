package io.browsercloud.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record UpsertExtensionProfileRequest(
    @NotBlank @Size(max = 128) String displayName,
    @Min(0) @Max(10_000) int staticCpuWeight,
    @Min(0) @Max(32_768) int staticMemoryWeight,
    @Min(0) @Max(10_000) int startupWeight,
    @Min(0) @Max(10_000) int pageInjectionWeight,
    @Min(0) @Max(32_768) int serviceWorkerWeight,
    @Min(0) @Max(10_000) int cryptoWeight,
    @Min(0) @Max(10_000) int networkWeight,
    @NotNull @DecimalMin("0.500") @DecimalMax("8.000") BigDecimal observedMultiplier,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
    @NotBlank @Pattern(regexp = "^(PROBATION|OBSERVED|CERTIFIED|DISABLED)$") String profileState,
    boolean web3,
    boolean serviceWorker,
    boolean crypto,
    boolean privileged) {}
