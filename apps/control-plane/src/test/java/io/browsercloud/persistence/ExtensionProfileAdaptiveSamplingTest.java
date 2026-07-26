package io.browsercloud.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExtensionProfileAdaptiveSamplingTest {

  @Test
  void usesP95WindowBeforePromotingAndGraduallyReducesSamplingFrequency() {
    var now = Instant.parse("2026-07-26T00:00:00Z");
    var profile =
        new ExtensionProfileEntity(
            "extension.wallet",
            "Wallet",
            100,
            200,
            0,
            0,
            0,
            0,
            0,
            BigDecimal.ONE,
            new BigDecimal("0.9000"),
            "OBSERVED",
            true,
            false,
            false,
            false,
            now);

    profile.applyObservation(1, 1000, 1000, false, now.plusSeconds(1));
    assertThat(profile.getObservedMultiplier()).isEqualByComparingTo("1");
    assertThat(profile.getSamplingTier()).isEqualTo("HIGH");

    profile.applyObservation(20, 160, 250, false, now.plusSeconds(2));
    assertThat(profile.getObservedMultiplier()).isEqualByComparingTo("1.6000");
    assertThat(profile.getSamplingTier()).isEqualTo("HIGH");

    profile.applyObservation(21, 100, 200, false, now.plusSeconds(3));
    profile.applyObservation(22, 100, 200, false, now.plusSeconds(4));
    profile.applyObservation(23, 100, 200, false, now.plusSeconds(5));
    assertThat(profile.getSamplingTier()).isEqualTo("MEDIUM");
    assertThat(profile.getNextSampleAt()).isEqualTo(now.plusSeconds(125));

    profile.applyObservation(24, 100, 200, true, now.plusSeconds(6));
    assertThat(profile.getSamplingTier()).isEqualTo("DEEP");
    assertThat(profile.getNextSampleAt()).isEqualTo(now.plusSeconds(21));
    assertThat(profile.getSamplingCpuBudgetMillis()).isEqualTo(25);
  }
}
