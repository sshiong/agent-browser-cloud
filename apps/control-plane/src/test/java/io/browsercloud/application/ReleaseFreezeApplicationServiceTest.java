package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReleaseFreezeApplicationServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

  @Test
  void freezesImmediatelyWhenBurnRateReachesConfiguredThreshold() {
    var decision =
        ReleaseFreezeApplicationService.decide(
            true,
            false,
            null,
            new BigDecimal("2.000000"),
            new BigDecimal("2.000000"),
            new BigDecimal("0.500000"),
            30,
            NOW);

    assertThat(decision.frozen()).isTrue();
    assertThat(decision.phase()).isEqualTo("FROZEN");
    assertThat(decision.transition()).isEqualTo(ReleaseFreezeApplicationService.Transition.FROZEN);
    assertThat(decision.stableSince()).isNull();
  }

  @Test
  void requiresContinuousLowBurnWindowBeforeClearingFreeze() {
    var observing =
        ReleaseFreezeApplicationService.decide(
            true,
            true,
            null,
            new BigDecimal("0.250000"),
            BigDecimal.ONE,
            new BigDecimal("0.500000"),
            30,
            NOW);
    assertThat(observing.phase()).isEqualTo("RECOVERING");
    assertThat(observing.stableSince()).isEqualTo(NOW);
    assertThat(observing.transition()).isNull();

    var cleared =
        ReleaseFreezeApplicationService.decide(
            true,
            true,
            NOW.minusSeconds(30 * 60L),
            new BigDecimal("0.250000"),
            BigDecimal.ONE,
            new BigDecimal("0.500000"),
            30,
            NOW);
    assertThat(cleared.frozen()).isFalse();
    assertThat(cleared.phase()).isEqualTo("OPEN");
    assertThat(cleared.transition()).isEqualTo(ReleaseFreezeApplicationService.Transition.CLEARED);
  }

  @Test
  void reboundAboveRecoveryThresholdResetsStableWindow() {
    var decision =
        ReleaseFreezeApplicationService.decide(
            true,
            true,
            NOW.minusSeconds(25 * 60L),
            new BigDecimal("0.750000"),
            BigDecimal.ONE,
            new BigDecimal("0.500000"),
            30,
            NOW);

    assertThat(decision.frozen()).isTrue();
    assertThat(decision.phase()).isEqualTo("FROZEN");
    assertThat(decision.stableSince()).isNull();
    assertThat(decision.transition()).isNull();
  }

  @Test
  void disablingPolicyClearsAnExistingFreeze() {
    var decision =
        ReleaseFreezeApplicationService.decide(
            false,
            true,
            null,
            new BigDecimal("99"),
            BigDecimal.ONE,
            new BigDecimal("0.500000"),
            30,
            NOW);

    assertThat(decision.frozen()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo("POLICY_DISABLED");
    assertThat(decision.transition()).isEqualTo(ReleaseFreezeApplicationService.Transition.CLEARED);
  }
}
