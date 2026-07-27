package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.browsercloud.coordinator.NodeEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessRecoveryValidatorTest {

  private final BusinessRecoveryValidator validator = new BusinessRecoveryValidator();

  @Test
  void acceptsCompleteRecoveredBusinessPage() {
    var verdict =
        validator.validate(
            new NodeEvent.StateUpdated(
                "ses-test",
                3,
                3,
                "https://crm.example.test/customers/42",
                "Customer",
                "hash",
                "COMPLETE",
                List.of()));

    assertThat(verdict.ready()).isTrue();
    assertThat(verdict.code()).isEqualTo("READY_DEFAULT_BROWSER_STATE_VALIDATOR");
  }

  @Test
  void keepsLoginPageDegradedForHumanRecovery() {
    var verdict =
        validator.validate(
            new NodeEvent.StateUpdated(
                "ses-test",
                3,
                3,
                "https://crm.example.test/sign-in",
                "Sign in",
                "hash",
                "COMPLETE",
                List.of()));

    assertThat(verdict.ready()).isFalse();
    assertThat(verdict.code()).isEqualTo("LOGIN_REQUIRED");
  }
}
