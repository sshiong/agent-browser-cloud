package io.browsercloud.api;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public final class UserPreferenceModels {
  private UserPreferenceModels() {}

  public enum ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
  }

  public record UpdateUserPreferencesRequest(@NotNull ThemeMode themeMode) {}

  public record UserPreferencesView(
      ThemeMode themeMode, String source, Instant updatedAt, long version) {}
}
