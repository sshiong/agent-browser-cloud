package io.browsercloud.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StateResyncRequest(
    @NotNull Mode mode, @Size(max = 512) String rootRef, @Size(max = 128) String reason) {

  public enum Mode {
    FULL,
    REGION
  }
}
