package io.browsercloud.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompleteKeyRotationRequest(
    boolean newKeyWriteVerified,
    boolean oldKeyReadVerified,
    boolean plaintextRejected,
    @Min(1) int affectedWorkloads,
    @NotBlank @Size(min = 8, max = 500) String verificationReference) {}
