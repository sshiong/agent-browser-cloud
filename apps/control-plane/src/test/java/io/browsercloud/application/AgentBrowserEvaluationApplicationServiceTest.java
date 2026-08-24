package io.browsercloud.application;

import static io.browsercloud.api.AgentBrowserEvaluationModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.browsercloud.api.AgentBrowserPerceptionModels.SnapshotView;
import io.browsercloud.api.BrowserStateView;
import io.browsercloud.application.AgentBrowserEvaluationStore.EvaluationRecord;
import io.browsercloud.application.AgentBrowserEvaluationStore.EvaluationRejectedException;
import io.browsercloud.domain.agent.AgentModels.AgentControlMode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentBrowserEvaluationApplicationServiceTest {
  private static final String SESSION_ID = "ses_1234567890abcdef";
  private static final String CURSOR = "9:4:" + "a".repeat(64);

  @Mock private AgentBrowserEvaluationStore store;
  @Mock private AgentBrowserPerceptionService perception;
  @Mock private AgentControlPolicyService controlPolicy;
  @Mock private AgentActionPayloadService payloads;
  private AgentBrowserEvaluationApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new AgentBrowserEvaluationApplicationService(
            store, perception, controlPolicy, new PromptSecurityService(), payloads);
  }

  @Test
  void sealsSourceAndClaimsAnExactStateFencedReadOnlyEvaluation() {
    var record = mock(EvaluationRecord.class);
    var view = mock(EvaluationView.class);
    when(record.evaluationId()).thenReturn("aje_1234567890abcdefghij");
    when(controlPolicy.require(SESSION_ID, "tenant-test"))
        .thenReturn(new AgentControlPolicyService.Policy(AgentControlMode.SAFE, 3));
    when(perception.snapshot(SESSION_ID, "tenant-test")).thenReturn(snapshot());
    when(payloads.seal(anyString(), anyString(), eq("expression"), anyString()))
        .thenReturn("sealed-source");
    when(store.claim(any())).thenReturn(record);
    when(store.get("aje_1234567890abcdefghij", "tenant-test", "agent-worker")).thenReturn(view);

    var result =
        service.create(
            SESSION_ID,
            "tenant-test",
            "agent-worker",
            "idem-evaluate-1",
            "request-test",
            new CreateEvaluationRequest(
                "read the visible heading",
                EvaluationMode.READ_ONLY,
                "document.querySelector('h1')?.textContent",
                CURSOR,
                null,
                null,
                null));

    assertThat(result).isSameAs(view);
    var claimed = ArgumentCaptor.forClass(AgentBrowserEvaluationStore.Claim.class);
    verify(store).claim(claimed.capture());
    assertThat(claimed.getValue().mode()).isEqualTo(EvaluationMode.READ_ONLY);
    assertThat(claimed.getValue().sealedExpression()).isEqualTo("sealed-source");
    assertThat(claimed.getValue().expressionSha256()).hasSize(64);
    assertThat(claimed.getValue().expectedStateVersion()).isEqualTo(9);
    assertThat(claimed.getValue().expectedTargetRevision()).isEqualTo(4);
    assertThat(claimed.getValue().expectedActiveTabId()).isEqualTo("tab-login");
    verify(payloads)
        .seal(
            eq("tenant-test"),
            startsWith("aje_"),
            eq("expression"),
            eq("document.querySelector('h1')?.textContent"));
  }

  @Test
  void rejectsCredentialStorageAndNetworkEscapeSourcesBeforeSealing() {
    assertThatThrownBy(
            () ->
                service.create(
                    SESSION_ID,
                    "tenant-test",
                    "agent-worker",
                    "idem-evaluate-2",
                    "request-test",
                    new CreateEvaluationRequest(
                        "read page data",
                        EvaluationMode.READ_ONLY,
                        "fetch('/private').then(r => r.text())",
                        CURSOR,
                        true,
                        2_000,
                        16_384)))
        .isInstanceOf(EvaluationRejectedException.class)
        .hasMessage("EVALUATION_FORBIDDEN_BROWSER_SOURCE");
    verifyNoInteractions(store, perception, controlPolicy, payloads);
  }

  @Test
  void rejectsAStaleCursorBeforeSealingOrClaiming() {
    when(controlPolicy.require(SESSION_ID, "tenant-test"))
        .thenReturn(new AgentControlPolicyService.Policy(AgentControlMode.AUTONOMOUS, 3));
    when(perception.snapshot(SESSION_ID, "tenant-test")).thenReturn(snapshot());

    assertThatThrownBy(
            () ->
                service.create(
                    SESSION_ID,
                    "tenant-test",
                    "agent-worker",
                    "idem-evaluate-3",
                    "request-test",
                    new CreateEvaluationRequest(
                        "set the local page filter",
                        EvaluationMode.PAGE_ACTION,
                        "document.querySelector('input').value = 'open'",
                        "8:4:" + "b".repeat(64),
                        true,
                        2_000,
                        16_384)))
        .isInstanceOf(EvaluationRejectedException.class)
        .hasMessage("STATE_CURSOR_STALE");
    verifyNoInteractions(store, payloads);
  }

  private static SnapshotView snapshot() {
    var state =
        new BrowserStateView(
            SESSION_ID,
            2,
            9,
            4,
            "https://example.test/login",
            "Login",
            "a".repeat(64),
            "COMPLETE",
            "complete",
            1000,
            true,
            List.of(),
            List.of(
                new BrowserStateView.BrowserTabView(
                    "tab-login", "https://example.test/login", "Login", true)),
            "tab-login");
    return new SnapshotView(CURSOR, state);
  }
}
