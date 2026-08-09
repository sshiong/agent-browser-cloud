package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecoveryGameDayGovernanceApplicationServiceTest {

  @Test
  void roundTripsStableTimelineCursor() {
    var gameDayId = "gameday_1234567890abcdefghij";
    var occurredAt = Instant.parse("2026-08-09T08:10:12.123456Z");
    var eventId = "gev_1234567890abcdefghij";

    var cursor =
        RecoveryGameDayGovernanceApplicationService.encodeCursor(gameDayId, occurredAt, eventId);

    assertThat(RecoveryGameDayGovernanceApplicationService.decodeCursor(gameDayId, cursor))
        .isEqualTo(
            new RecoveryGameDayGovernanceApplicationService.CursorPosition(occurredAt, eventId));
  }

  @Test
  void rejectsMalformedOrUnboundedTimelineCursor() {
    assertThatThrownBy(
            () ->
                RecoveryGameDayGovernanceApplicationService.decodeCursor(
                    "gameday_1234567890abcdefghij", "not-a-cursor"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid GameDay event cursor");

    var wrongResource =
        RecoveryGameDayGovernanceApplicationService.encodeCursor(
            "gameday_abcdefghij1234567890",
            Instant.parse("2026-08-09T08:10:12Z"),
            "gev_1234567890abcdefghij");
    assertThatThrownBy(
            () ->
                RecoveryGameDayGovernanceApplicationService.decodeCursor(
                    "gameday_1234567890abcdefghij", wrongResource))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid GameDay event cursor");
  }
}
