package io.browsercloud.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BrowserNodeFreshnessProjectionStoreTest {

  @Test
  void projectsOnlyCrossInstanceDeduplicatedFreshnessTransitions() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(
            anyString(),
            eq(Integer.class),
            any(Timestamp.class),
            any(Timestamp.class),
            any(Timestamp.class)))
        .thenReturn(2);
    var store = new BrowserNodeFreshnessProjectionStore(jdbc);
    var freshAfter = Instant.parse("2026-08-17T00:00:00Z");
    var observedAt = Instant.parse("2026-08-17T00:01:00Z");

    assertThat(store.projectTransitions(freshAfter, observedAt)).isEqualTo(2);

    verify(jdbc)
        .queryForObject(
            org.mockito.ArgumentMatchers.contains("IS DISTINCT FROM EXCLUDED.freshness_state"),
            eq(Integer.class),
            eq(Timestamp.from(freshAfter)),
            eq(Timestamp.from(observedAt)),
            eq(Timestamp.from(observedAt)));
  }
}
