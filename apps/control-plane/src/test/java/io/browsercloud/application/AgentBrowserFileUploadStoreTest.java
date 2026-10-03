package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.browsercloud.coordinator.*;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentBrowserFileUploadStoreTest {
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final BrowserStateRepository states = mock(BrowserStateRepository.class);
  private final OperationRepository operations = mock(OperationRepository.class);
  private final NodeCommandGateway commands = mock(NodeCommandGateway.class);
  private final AuditApplicationService audit = mock(AuditApplicationService.class);
  private final AgentBrowserFileUploadStore store =
      new AgentBrowserFileUploadStore(
          mock(JdbcTemplate.class), sessions, states, operations, commands, audit);

  @Test
  void rejectsNonInteractiveFileTargetBeforeCreatingOperation() {
    prepare(false);
    assertThatThrownBy(() -> store.claim(claim()))
        .isInstanceOf(AgentBrowserFileUploadStore.FileUploadRejectedException.class)
        .hasMessage("FILE_INPUT_TARGET_INVALID");
    verifyNoInteractions(operations, commands, audit);
  }

  @Test
  void interactiveHiddenFileTargetStillRequiresOperationAdmission() {
    prepare(true);
    doThrow(new IllegalStateException("OPERATION_ADMISSION_REACHED"))
        .when(operations)
        .ensureNoActiveOperation("ses_file");
    assertThatThrownBy(() -> store.claim(claim()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("OPERATION_ADMISSION_REACHED");
    verifyNoInteractions(commands, audit);
  }

  private void prepare(boolean interactive) {
    var session = mock(SessionContext.class);
    when(session.sessionId()).thenReturn("ses_file");
    when(session.tenantId()).thenReturn("tenant-test");
    when(session.nodeId()).thenReturn("node-test");
    when(session.contextEpoch()).thenReturn(1L);
    when(session.state()).thenReturn(SessionState.RUNNING);
    when(sessions.requireForUpdate("ses_file")).thenReturn(session);
    var target =
        new NodeEvent.InteractiveTarget(
            "target-file",
            "textbox",
            "File",
            null,
            true,
            false,
            false,
            "e0123456789abcdef012345678",
            null,
            "file",
            false,
            null,
            null,
            interactive,
            "main",
            false,
            false,
            null);
    var state =
        new NodeEvent.StateUpdated(
            "ses_file",
            7,
            7,
            "https://example.test/",
            "Test",
            "state-hash",
            "COMPLETE",
            List.of(target));
    when(states.find("ses_file"))
        .thenReturn(Optional.of(new BrowserStateRepository.Snapshot("tenant-test", 1, state)));
  }

  private static AgentBrowserFileUploadStore.UploadClaim claim() {
    return new AgentBrowserFileUploadStore.UploadClaim(
        "afu_test",
        "tenant-test",
        "ses_file",
        "actor-test",
        "idempotency-test",
        "request-hash",
        "request-test",
        "target-file",
        7,
        7,
        "state-hash",
        "fixture.txt",
        "text/plain",
        "content-hash",
        1);
  }
}
