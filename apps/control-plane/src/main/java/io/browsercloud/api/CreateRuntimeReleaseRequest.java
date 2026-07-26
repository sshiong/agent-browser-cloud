package io.browsercloud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRuntimeReleaseRequest(
    @NotBlank @Pattern(regexp = "^(CANARY|STABLE)$") String targetChannel,
    @NotBlank @Size(min = 20, max = 500) String reason) {}
