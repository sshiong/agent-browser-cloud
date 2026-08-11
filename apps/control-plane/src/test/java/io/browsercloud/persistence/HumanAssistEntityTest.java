package io.browsercloud.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class HumanAssistEntityTest {

  @Test
  void resolvesATakeoverOnlyChallengeAfterTheHumanReleasesControl() {
    var now = Instant.parse("2026-08-12T02:00:00Z");
    var event =
        new ChallengeEventEntity(
            "chl_1234567890abcdefghij",
            "tenant-test",
            "ses_1234567890abcdef",
            2,
            12,
            7,
            0.98,
            "{}",
            "OTP",
            "CHALLENGE_CONFIRMED",
            null,
            "验证码需要人工接管",
            null,
            "TAKEOVER_REQUIRED",
            now,
            now.plusSeconds(120),
            now.plusSeconds(300));

    event.supersede(now.plusSeconds(9));
    event.resolvedByHumanTakeover(now.plusSeconds(10));

    assertThat(event.getStatus()).isEqualTo("RESOLVED");
    assertThat(event.isTerminal()).isTrue();
  }

  @Test
  void consumesExactlyOneActionAndCannotBeReused() {
    var now = Instant.parse("2026-08-12T02:00:00Z");
    var intent = intent(now);

    intent.consume("op_1234567890abcdef", now.plusSeconds(1));

    assertThat(intent.getAllowedActionCount()).isOne();
    assertThat(intent.getConsumedCount()).isOne();
    assertThat(intent.getState()).isEqualTo("EXECUTING");
    assertThatThrownBy(() -> intent.consume("op_other", now.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already consumed");

    intent.committed(now.plusSeconds(3));
    assertThat(intent.getState()).isEqualTo("COMMITTED");
  }

  @Test
  void failedExecutionCannotRetryOrCommit() {
    var now = Instant.parse("2026-08-12T02:00:00Z");
    var intent = intent(now);
    intent.consume("op_1234567890abcdef", now.plusSeconds(1));
    intent.failed("TARGET_MOVED", now.plusSeconds(2));

    assertThat(intent.getState()).isEqualTo("FAILED");
    assertThat(intent.getErrorCode()).isEqualTo("TARGET_MOVED");
    assertThatThrownBy(() -> intent.committed(now.plusSeconds(3)))
        .isInstanceOf(IllegalStateException.class);
  }

  private static HumanClickIntentEntity intent(Instant now) {
    return new HumanClickIntentEntity(
        "hint_1234567890abcdefghij",
        "chl_1234567890abcdefghij",
        "tenant-test",
        "user-test",
        "ses_1234567890abcdef",
        2,
        12,
        7,
        "{\"x\":10,\"y\":20,\"width\":100,\"height\":30}",
        "target:7:abc",
        "6dc7a8367775c215991f36f2d4553d38a64f6e5df58b813cc77d8d8e448647a5",
        "evt_1234567890abcdef",
        "req-test",
        "idem-test",
        now.plusSeconds(120),
        now);
  }
}
