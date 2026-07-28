package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class NodeCommandDispatchClaimServiceTest {

  @Test
  void validatesCrashRecoveryLeaseBounds() {
    var jdbc = mock(NamedParameterJdbcTemplate.class);
    assertThatThrownBy(() -> new NodeCommandDispatchClaimService(jdbc, "coordinator-invalid", 6, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("worker-lease");
  }

  @Test
  @SuppressWarnings("unchecked")
  void claimsRowsWithSkipLockedQuery() {
    var jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(anyString(), any(java.util.Map.class), any(RowMapper.class)))
        .thenReturn(List.of("evt-one", "evt-two"));
    when(jdbc.update(anyString(), any(java.util.Map.class))).thenReturn(1);
    var worker = new NodeCommandDispatchClaimService(jdbc, "coordinator-0", 6, 3);

    assertThat(worker.claimReady(Instant.parse("2026-07-29T00:00:00Z")))
        .containsExactly("evt-one", "evt-two");
    assertThat(worker.workerId()).isEqualTo("coordinator-0");
  }

  @Test
  void rejectsInvalidPhysicalWorkerTopology() {
    var jdbc = mock(NamedParameterJdbcTemplate.class);

    assertThatThrownBy(() -> new NodeCommandDispatchClaimService(jdbc, "", 6, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("instance-id");
  }
}
