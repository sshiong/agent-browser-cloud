package io.browsercloud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProfileRequest(
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_-]{1,128}$") String profileId,
    @NotBlank @Size(max = 128) String name,
    @Size(max = 1024) String description) {}
