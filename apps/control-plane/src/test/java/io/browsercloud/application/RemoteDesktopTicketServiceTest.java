package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.operation.OwnerType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class RemoteDesktopTicketServiceTest {

  private static final String SECRET = "test-remote-desktop-ticket-secret-32-bytes";

  @Test
  void shouldIssueShortLivedTicketBoundToOperationAndActor() throws Exception {
    var clock = Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC);
    var service = new RemoteDesktopTicketService(new ObjectMapper(), SECRET, 45, "test", clock);

    var response =
        service.issue("tenant-test", "ses_1234567890abcdef", "user-test", takeoverOperation());

    assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-07-26T00:00:45Z"));
    assertThat(response.operationEpoch()).isEqualTo(7);
    assertThat(response.webSocketPath())
        .startsWith("/desktop/v1/sessions/ses_1234567890abcdef?ticket=");
    var ticket = response.webSocketPath().substring(response.webSocketPath().indexOf('=') + 1);
    var parts = ticket.split("\\.");
    assertThat(parts).hasSize(2);
    var payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
    assertThat(payload)
        .contains("\"tenantId\":\"tenant-test\"")
        .contains("\"sessionId\":\"ses_1234567890abcdef\"")
        .contains("\"actorId\":\"user-test\"")
        .contains("\"coordinatorTerm\":1")
        .contains("\"contextEpoch\":3")
        .contains("\"operationEpoch\":7");

    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    assertThat(Base64.getUrlDecoder().decode(parts[1]))
        .isEqualTo(mac.doFinal(parts[0].getBytes(StandardCharsets.US_ASCII)));
  }

  @Test
  void shouldRejectLocalSecretInProduction() {
    assertThatThrownBy(
            () ->
                new RemoteDesktopTicketService(
                    new ObjectMapper(),
                    RemoteDesktopTicketService.LOCAL_SECRET,
                    45,
                    "production",
                    Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be overridden");
  }

  private ExclusiveOperation takeoverOperation() {
    var now = Instant.parse("2026-07-26T00:00:00Z");
    return new ExclusiveOperation(
        "op_takeover",
        "ses_1234567890abcdef",
        OwnerType.HUMAN,
        "user-test",
        OperationMode.HUMAN_TAKEOVER,
        90,
        1,
        3,
        7,
        null,
        true,
        false,
        OperationPhase.EXECUTING,
        OperationState.ACTIVE,
        Set.of("desktop.control"),
        now.plusSeconds(3600),
        now,
        null);
  }
}
