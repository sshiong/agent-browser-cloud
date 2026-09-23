package io.browsercloud.api;

import static io.browsercloud.api.EnvironmentImportModels.PreviewEnvironmentImportRequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Portable, secret-free environment configuration export and clone contracts. */
public final class SessionEnvironmentModels {
  private SessionEnvironmentModels() {}

  public enum CloneProfileMode {
    /** Creates a deterministic, empty Profile. Cookies, storage, and login state are not copied. */
    NEW_EMPTY_PROFILE,
    /** Reuses the source Profile reference. Callers must avoid concurrent Profile writers. */
    REUSE_SOURCE_PROFILE
  }

  public record CloneEnvironmentRequest(
      @NotBlank @Size(max = 128) String displayName, @NotNull CloneProfileMode profileMode) {}

  public record CloneEnvironmentResponse(
      String sourceSessionId,
      CloneProfileMode profileMode,
      String targetProfileId,
      CreateSessionResponse session) {}

  public record EnvironmentConfigurationExportView(
      String sourceSessionId,
      Instant exportedAt,
      String manifestHash,
      PreviewEnvironmentImportRequest manifest,
      List<String> excludedData) {
    public EnvironmentConfigurationExportView {
      excludedData = List.copyOf(excludedData);
    }
  }
}
