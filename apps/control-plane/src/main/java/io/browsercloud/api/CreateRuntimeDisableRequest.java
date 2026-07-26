package io.browsercloud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRuntimeDisableRequest(@NotBlank @Size(min = 20, max = 500) String reason) {}
