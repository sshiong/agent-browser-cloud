package io.browsercloud.application;

import io.browsercloud.coordinator.NodeEvent;
import java.time.Duration;
import java.time.Instant;

/** Server-clock freshness and page activity derived from persisted Browser State evidence. */
final class BrowserStateFreshness {
  static final long FRESH_MAX_AGE_MILLIS = 10_000;
  static final long AGING_MAX_AGE_MILLIS = 30_000;
  static final long STABLE_NETWORK_QUIET_MILLIS = 2_000;

  private BrowserStateFreshness() {}

  static Description describe(NodeEvent.StateUpdated state, Instant observedAt, Instant now) {
    var safeObservedAt = observedAt == null ? Instant.EPOCH : observedAt;
    var ageMillis = Math.max(0, durationMillis(safeObservedAt, now));
    var evidenceUsable =
        state.networkEvidenceFresh()
            && !state.documentReadyState().isBlank()
            && !java.util.Set.of("INVALID", "RESYNCING", "DEGRADED").contains(state.stateQuality());
    var freshness =
        !evidenceUsable || ageMillis > AGING_MAX_AGE_MILLIS
            ? "STALE"
            : ageMillis > FRESH_MAX_AGE_MILLIS ? "AGING" : "FRESH";
    var activity = pageActivity(state, evidenceUsable);
    return new Description(safeObservedAt, ageMillis, freshness, activity);
  }

  private static String pageActivity(NodeEvent.StateUpdated state, boolean evidenceUsable) {
    if (!evidenceUsable) return "UNKNOWN";
    if (state.documentReadyState().equals("loading") || state.networkQuietMillis() <= 0) {
      return "CHANGING";
    }
    if (state.documentReadyState().equals("complete")
        && state.networkQuietMillis() >= STABLE_NETWORK_QUIET_MILLIS) {
      return "STABLE";
    }
    return "SETTLING";
  }

  private static long durationMillis(Instant from, Instant to) {
    try {
      return Duration.between(from, to).toMillis();
    } catch (ArithmeticException exception) {
      return Long.MAX_VALUE;
    }
  }

  record Description(Instant observedAt, long ageMillis, String freshness, String pageActivity) {}
}
