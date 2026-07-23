package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.coordinator.SessionDescriptor;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionApplicationServiceTest {

  @Mock private SessionCoordinator coordinator;
  @Mock private SessionRepository sessionRepository;
  @Mock private OperationRepository operationRepository;
  @Mock private BrowserStateRepository browserStateRepository;
  @Mock private IdempotencyService idempotencyService;

  private SessionApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new SessionApplicationService(
            coordinator,
            sessionRepository,
            operationRepository,
            browserStateRepository,
            idempotencyService,
            "runtime-test");
  }

  @Test
  void shouldExposeControlledSessionDescriptorFields() {
    var now = Instant.parse("2026-07-23T00:00:00Z");
    var context =
        new SessionContext(
            "ses_test",
            "tenant-test",
            "profile-test",
            null,
            null,
            null,
            null,
            0,
            0,
            0,
            0,
            ResourceClass.L2,
            SessionState.CREATED,
            "",
            now,
            now);
    when(sessionRepository.describe("ses_test"))
        .thenReturn(new SessionDescriptor(context, "local", "Integration browser"));
    when(operationRepository.findActive("ses_test")).thenReturn(Optional.empty());

    var view = service.get("ses_test", "tenant-test");

    assertThat(view.displayName()).isEqualTo("Integration browser");
    assertThat(view.profileId()).isEqualTo("profile-test");
    assertThat(view.region()).isEqualTo("local");
    assertThat(view.resourceClass()).isEqualTo(ResourceClass.L2);
  }
}
