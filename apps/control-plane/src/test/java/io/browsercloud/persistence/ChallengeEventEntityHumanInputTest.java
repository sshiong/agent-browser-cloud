package io.browsercloud.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChallengeEventEntityHumanInputTest {

  @Test
  void shouldReturnFailedOtpInputToTheSameHumanAssistanceState() {
    var now = Instant.parse("2026-08-19T00:00:00Z");
    var event = otpEvent(now);

    event.inputExecuting(now.plusSeconds(1));
    assertThat(event.getStatus()).isEqualTo("EXECUTING");

    event.inputAttemptFailed(now.plusSeconds(2));
    assertThat(event.getStatus()).isEqualTo("TAKEOVER_REQUIRED");

    event.inputExecuting(now.plusSeconds(3));
    event.resolved(now.plusSeconds(4));
    assertThat(event.getStatus()).isEqualTo("RESOLVED");
  }

  private static ChallengeEventEntity otpEvent(Instant now) {
    return new ChallengeEventEntity(
        "chl_1234567890abcdefghij",
        "tenant-test",
        "ses_1234567890abcdef",
        2,
        15,
        7,
        0.98,
        "{\"detector\":\"test\"}",
        "OTP",
        "CHALLENGE_CONFIRMED",
        "target:7:otp",
        "OTP requires a person",
        null,
        "TAKEOVER_REQUIRED",
        now,
        now.plusSeconds(120),
        now.plusSeconds(300));
  }
}
