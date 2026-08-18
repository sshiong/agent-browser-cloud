package io.browsercloud.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.persistence.EnterpriseOverviewStreamStore;
import org.junit.jupiter.api.Test;

class EnterpriseOverviewTemporalInvalidationSchedulerTest {

  @Test
  void drainsDueInvalidationsInBoundedCrossInstanceSafeBatches() {
    var store = mock(EnterpriseOverviewStreamStore.class);
    when(store.publishDueInvalidations(1_000)).thenReturn(1_000, 2);
    var scheduler = new EnterpriseOverviewTemporalInvalidationScheduler(store);

    scheduler.publishDueInvalidations();

    verify(store, org.mockito.Mockito.times(2)).publishDueInvalidations(1_000);
  }
}
