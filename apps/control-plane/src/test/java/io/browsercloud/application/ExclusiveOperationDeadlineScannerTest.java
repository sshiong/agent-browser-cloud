package io.browsercloud.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.infrastructure.ExclusiveOperationJpaRepository;
import io.browsercloud.persistence.ExclusiveOperationEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class ExclusiveOperationDeadlineScannerTest {

  @Test
  void routesEveryExpiredOperationWithoutADurableWorkflow() {
    var operations = mock(ExclusiveOperationJpaRepository.class);
    var routing = mock(CoordinatorCommandRoutingService.class);
    var operation = mock(ExclusiveOperationEntity.class);
    when(operation.getSessionId()).thenReturn("ses_1234567890abcdef");
    when(operation.getOperationId()).thenReturn("op_1234567890abcdef");
    when(operations.findExpiredWithoutWorkflow(any(), any(Pageable.class)))
        .thenReturn(List.of(operation));

    new ExclusiveOperationDeadlineScanner(operations, routing).scan();

    verify(routing)
        .enqueueAsync(
            eq("ses_1234567890abcdef"),
            eq(CoordinatorCommandPayloads.OPERATION_TIMEOUT),
            eq("operation-timeout:op_1234567890abcdef"),
            any(CoordinatorCommandPayloads.OperationTimeout.class),
            any());
  }
}
