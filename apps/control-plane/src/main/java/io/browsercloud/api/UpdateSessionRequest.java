package io.browsercloud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Mutable operator-owned Session presentation fields. */
public record UpdateSessionRequest(@NotBlank @Size(max = 128) String displayName) {}
