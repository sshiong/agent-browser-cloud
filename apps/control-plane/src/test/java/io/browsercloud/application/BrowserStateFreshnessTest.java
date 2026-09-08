package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.browsercloud.coordinator.NodeEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrowserStateFreshnessTest {
  private static final Instant NOW = Instant.parse("2026-09-07T09:00:00Z");

  @Test
  void classifiesAgeUsingControlPlaneObservedTime() {
    assertThat(describe(state("complete", 3_000, true, "COMPLETE"), 5).freshness())
        .isEqualTo("FRESH");
    assertThat(describe(state("complete", 3_000, true, "COMPLETE"), 20).freshness())
        .isEqualTo("AGING");
    assertThat(describe(state("complete", 3_000, true, "COMPLETE"), 31).freshness())
        .isEqualTo("STALE");
  }

  @Test
  void failsFreshnessClosedWhenEvidenceIsNotUsable() {
    assertThat(describe(state("complete", 3_000, false, "COMPLETE"), 1))
        .extracting(
            BrowserStateFreshness.Description::freshness,
            BrowserStateFreshness.Description::pageActivity)
        .containsExactly("STALE", "UNKNOWN");
    assertThat(describe(state("complete", 3_000, true, "DEGRADED"), 1).freshness())
        .isEqualTo("STALE");
  }

  @Test
  void separatesChangingSettlingAndStablePageActivity() {
    assertThat(describe(state("loading", 0, true, "COMPLETE"), 1).pageActivity())
        .isEqualTo("CHANGING");
    assertThat(describe(state("interactive", 500, true, "COMPLETE"), 1).pageActivity())
        .isEqualTo("SETTLING");
    assertThat(describe(state("complete", 2_000, true, "COMPLETE"), 1).pageActivity())
        .isEqualTo("STABLE");
  }

  private static BrowserStateFreshness.Description describe(
      NodeEvent.StateUpdated state, long age) {
    return BrowserStateFreshness.describe(state, NOW.minusSeconds(age), NOW);
  }

  private static NodeEvent.StateUpdated state(
      String readyState, long networkQuietMillis, boolean networkFresh, String quality) {
    return new NodeEvent.StateUpdated(
        "ses_test",
        7,
        3,
        "https://example.test/app",
        "App",
        List.of(),
        "",
        "hash-7",
        quality,
        List.of(),
        readyState,
        networkQuietMillis,
        networkFresh,
        "PERIODIC",
        "");
  }
}
