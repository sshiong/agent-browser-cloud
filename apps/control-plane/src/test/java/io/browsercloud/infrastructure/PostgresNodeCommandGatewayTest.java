package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.CoordinatorRouteAuthority;
import io.browsercloud.coordinator.NodeCommand;
import io.browsercloud.persistence.OutboxEventEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PostgresNodeCommandGatewayTest {

  @Test
  void capturesAuthoritativeRouteInTheSameOutboxWrite() {
    var repository = mock(OutboxEventJpaRepository.class);
    var routeAuthority = mock(CoordinatorRouteAuthority.class);
    when(routeAuthority.resolve("ses_routed"))
        .thenReturn(
            new CoordinatorRouteAuthority.SessionRoute("ses_routed", "tenant-test", 7, 3, 11));
    var gateway = new PostgresNodeCommandGateway(repository, new ObjectMapper(), routeAuthority);

    gateway.send(
        new NodeCommand(
            "msg-routed",
            "StopRuntime",
            "node-test",
            "ses_routed",
            "tenant-test",
            4,
            5,
            6,
            "operation-test",
            new byte[0]));

    var saved = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getRouteEpoch()).isEqualTo(7);
    assertThat(saved.getValue().getCoordinatorShardId()).isEqualTo(11);
    assertThat(saved.getValue().getDispatchOwner()).isNull();
    verify(routeAuthority).resolve("ses_routed");
  }
}
