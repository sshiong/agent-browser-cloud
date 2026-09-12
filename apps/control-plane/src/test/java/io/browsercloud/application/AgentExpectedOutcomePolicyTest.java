package io.browsercloud.application;

import static io.browsercloud.api.AgentExpectedOutcomeModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.browsercloud.coordinator.NodeEvent.Bounds;
import io.browsercloud.coordinator.NodeEvent.InteractiveTarget;
import io.browsercloud.coordinator.NodeEvent.StateUpdated;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentExpectedOutcomePolicyTest {

  @Test
  void normalizesRawValuesToHashesAndEvaluatesExactUrlTitleAndTargetState() {
    var definitions =
        AgentExpectedOutcomePolicy.normalize(
            List.of(
                request(
                    "url",
                    ExpectedOutcomeType.FINAL_URL_EQUALS,
                    null,
                    "HTTPS://Example.Test/saved?token=secret"),
                request("title", ExpectedOutcomeType.PAGE_TITLE_EQUALS, null, "  Saved   Record  "),
                request("checked", ExpectedOutcomeType.TARGET_CHECKED, "checkbox", "Remember me")),
            List.of("example.test"));

    assertThat(definitions).hasSize(3);
    assertThat(definitions.toString()).doesNotContain("secret", "Saved Record", "Remember me");
    assertThat(definitions).allMatch(item -> item.expectedValueHash().matches("^[a-f0-9]{64}$"));

    var evaluations =
        AgentExpectedOutcomePolicy.evaluate(
            definitions,
            state(
                "https://example.test/saved?different=private",
                "saved record",
                "COMPLETE",
                List.of(target("checkbox", "Remember me", true))));

    assertThat(evaluations).allMatch(item -> item.status() == ExpectedOutcomeStatus.SATISFIED);
    assertThat(evaluations.get(0).observedValueHash()).matches("^[a-f0-9]{64}$");
  }

  @Test
  void missingTargetIsIndeterminateForDepthLimitedStateAndFailedForCompleteState() {
    var definition =
        AgentExpectedOutcomePolicy.normalize(
                List.of(
                    request(
                        "saved", ExpectedOutcomeType.TARGET_PRESENT, "status", "Changes saved")),
                List.of("example.test"))
            .getFirst();

    assertThat(
            AgentExpectedOutcomePolicy.evaluate(
                    List.of(definition),
                    state("https://example.test/", "Page", "DEPTH_LIMITED", List.of()))
                .getFirst()
                .status())
        .isEqualTo(ExpectedOutcomeStatus.INDETERMINATE);
    assertThat(
            AgentExpectedOutcomePolicy.evaluate(
                    List.of(definition),
                    state("https://example.test/", "Page", "COMPLETE", List.of()))
                .getFirst()
                .status())
        .isEqualTo(ExpectedOutcomeStatus.NOT_SATISFIED);
  }

  @Test
  void rejectsDuplicateIdsCrossDomainUrlsAndInvalidRoleShapes() {
    assertThatThrownBy(
            () ->
                AgentExpectedOutcomePolicy.normalize(
                    List.of(
                        request("same", ExpectedOutcomeType.PAGE_TITLE_EQUALS, null, "One"),
                        request("same", ExpectedOutcomeType.PAGE_TITLE_EQUALS, null, "Two")),
                    List.of("example.test")))
        .hasMessage("EXPECTED_OUTCOME_ID_DUPLICATE");
    assertThatThrownBy(
            () ->
                AgentExpectedOutcomePolicy.normalize(
                    List.of(
                        request(
                            "url",
                            ExpectedOutcomeType.FINAL_URL_EQUALS,
                            null,
                            "https://evil.test/")),
                    List.of("example.test")))
        .hasMessage("EXPECTED_OUTCOME_URL_DOMAIN_NOT_ALLOWED");
    assertThatThrownBy(
            () ->
                AgentExpectedOutcomePolicy.normalize(
                    List.of(
                        request(
                            "target", ExpectedOutcomeType.TARGET_PRESENT, null, "Changes saved")),
                    List.of("example.test")))
        .hasMessage("EXPECTED_OUTCOME_ROLE_REQUIRED");
  }

  private static ExpectedOutcomeRequest request(
      String id, ExpectedOutcomeType type, String role, String value) {
    return new ExpectedOutcomeRequest(id, type, role, value);
  }

  private static StateUpdated state(
      String url, String title, String quality, List<InteractiveTarget> targets) {
    return new StateUpdated(
        "ses_1234567890abcdefghij",
        2,
        2,
        url,
        title,
        "a".repeat(64),
        quality,
        targets,
        "complete",
        1000,
        true,
        "FULL",
        "");
  }

  private static InteractiveTarget target(String role, String name, boolean checked) {
    return new InteractiveTarget(
        "target-ref",
        role,
        name,
        new Bounds(0, 0, 10, 10),
        true,
        true,
        false,
        "element-id",
        null,
        role,
        false,
        checked,
        null,
        true,
        "main",
        true,
        false,
        "VISIBLE");
  }
}
