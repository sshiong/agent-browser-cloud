package io.browsercloud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Public declarations and minimized evidence for deterministic Task outcome verification. */
public final class AgentExpectedOutcomeModels {

  private AgentExpectedOutcomeModels() {}

  public enum ExpectedOutcomeType {
    FINAL_URL_EQUALS,
    PAGE_TITLE_EQUALS,
    TARGET_PRESENT,
    TARGET_ABSENT,
    TARGET_CHECKED,
    TARGET_UNCHECKED,
    TARGET_SELECTED,
    TARGET_UNSELECTED
  }

  public enum ExpectedOutcomeStatus {
    SATISFIED,
    NOT_SATISFIED,
    INDETERMINATE
  }

  /** matchValue is accepted only at creation and is never persisted or returned. */
  public record ExpectedOutcomeRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$") String outcomeId,
      @NotNull ExpectedOutcomeType type,
      @Size(max = 64) @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]{0,63}$") String role,
      @NotBlank @Size(max = 512) String matchValue) {}

  /** Canonical declaration persisted with the Task; contains hashes instead of matchValue. */
  public record ExpectedOutcomeDefinition(
      String outcomeId, ExpectedOutcomeType type, String role, String expectedValueHash) {}

  /** Deterministic evaluation against one exact final Browser State. */
  public record ExpectedOutcomeEvaluation(
      String outcomeId,
      ExpectedOutcomeType type,
      ExpectedOutcomeStatus status,
      String reasonCode,
      String observedValueHash) {}
}
