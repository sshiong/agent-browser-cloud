package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.persistence.BrowserStateEntity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JpaBrowserStateRepositoryTest {

  @Test
  void shouldAtomicallyReplaceRegionTargetsAndPersistRegionProvenance() throws Exception {
    var jpa = mock(BrowserStateJpaRepository.class);
    when(jpa.findById("ses_test")).thenReturn(Optional.empty());
    when(jpa.save(any(BrowserStateEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var objectMapper = new ObjectMapper();
    var repository = new JpaBrowserStateRepository(jpa, objectMapper);
    var outside = target("target:3:outside", "Outside");
    var oldInside = target("target:3:old", "Old inside");
    repository.save(
        "tenant-test",
        2,
        new NodeEvent.StateUpdated(
            "ses_test",
            7,
            3,
            "https://example.test/app",
            "App",
            List.of(new NodeEvent.BrowserTab("tab-app", "https://example.test/app", "App", true)),
            "tab-app",
            "hash-7",
            "COMPLETE",
            List.of(outside, oldInside),
            "complete",
            1_000,
            true,
            "FULL",
            ""));

    var saved = ArgumentCaptor.forClass(BrowserStateEntity.class);
    verify(jpa).save(saved.capture());
    var entity = saved.getValue();
    when(jpa.findByIdForUpdate("ses_test")).thenReturn(Optional.of(entity));
    var replacement = target("target:3:new", "New inside");
    var applied =
        repository.applyDiff(
            "tenant-test",
            2,
            new NodeEvent.StateDiff(
                "ses_test",
                7,
                8,
                3,
                "https://example.test/app",
                "App",
                List.of(
                    new NodeEvent.BrowserTab("tab-app", "https://example.test/app", "App", true)),
                "tab-app",
                "hash-8",
                "COMPLETE",
                "complete",
                1_000,
                true,
                List.of(replacement),
                List.of("target:3:old"),
                "REGION_RESYNC",
                "#app"));

    assertThat(applied).isTrue();
    var merged = objectMapper.readValue(entity.getStateJson(), NodeEvent.StateUpdated.class);
    assertThat(merged.stateVersion()).isEqualTo(8);
    assertThat(merged.targetRevision()).isEqualTo(3);
    assertThat(merged.activeTabId()).isEqualTo("tab-app");
    assertThat(merged.tabs())
        .singleElement()
        .extracting(NodeEvent.BrowserTab::active)
        .isEqualTo(true);
    assertThat(merged.targets()).containsExactly(outside, replacement);
    assertThat(merged.snapshotKind()).isEqualTo("REGION_RESYNC");
    assertThat(merged.requestedRootRef()).isEqualTo("#app");
  }

  @Test
  void shouldRejectRegionReplacementWhenBaseVersionDoesNotMatch() {
    var jpa = mock(BrowserStateJpaRepository.class);
    var entity = new BrowserStateEntity();
    entity.setSessionId("ses_test");
    entity.setTenantId("tenant-test");
    entity.setContextEpoch(2);
    entity.setStateVersion(8);
    when(jpa.findByIdForUpdate("ses_test")).thenReturn(Optional.of(entity));
    var repository = new JpaBrowserStateRepository(jpa, new ObjectMapper());

    var applied =
        repository.applyDiff(
            "tenant-test",
            2,
            new NodeEvent.StateDiff(
                "ses_test",
                7,
                9,
                3,
                "https://example.test/app",
                "App",
                "hash-9",
                "COMPLETE",
                List.of(),
                List.of()));

    assertThat(applied).isFalse();
  }

  @Test
  void shouldPreserveLastNativeDialogProjectionAcrossObserverGap() throws Exception {
    var jpa = mock(BrowserStateJpaRepository.class);
    var objectMapper = new ObjectMapper();
    var tab = new NodeEvent.BrowserTab("tab-app", "https://example.test/app", "App", true);
    var dialog =
        new NodeEvent.NativeDialog(
            "dlg_0123456789abcdef0123", "tab-app", "CONFIRM", "Continue?", "", false);
    var previous =
        new NodeEvent.StateUpdated(
            "ses_test",
            7,
            3,
            "https://example.test/app",
            "App",
            List.of(tab),
            "tab-app",
            "hash-7",
            "COMPLETE",
            List.of(),
            "complete",
            1_000,
            true,
            "PERIODIC",
            "",
            List.of(),
            List.of(dialog),
            true);
    var entity = new BrowserStateEntity();
    entity.setSessionId("ses_test");
    entity.setTenantId("tenant-test");
    entity.setContextEpoch(2);
    entity.setStateVersion(7);
    entity.setStateJson(objectMapper.writeValueAsString(previous));
    when(jpa.findById("ses_test")).thenReturn(Optional.of(entity));
    when(jpa.save(any(BrowserStateEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var repository = new JpaBrowserStateRepository(jpa, objectMapper);

    repository.save(
        "tenant-test",
        2,
        new NodeEvent.StateUpdated(
            "ses_test",
            8,
            3,
            "https://example.test/app",
            "App",
            List.of(tab),
            "tab-app",
            "hash-8",
            "COMPLETE",
            List.of(),
            "complete",
            1_500,
            true,
            "PERIODIC",
            "",
            List.of(),
            List.of(),
            false));

    var persisted = objectMapper.readValue(entity.getStateJson(), NodeEvent.StateUpdated.class);
    assertThat(persisted.nativeDialogs()).containsExactly(dialog);
    assertThat(persisted.nativeDialogEvidenceFresh()).isFalse();
    assertThat(persisted.stateQuality()).isEqualTo("DEGRADED");
  }

  private static NodeEvent.InteractiveTarget target(String targetRef, String name) {
    return new NodeEvent.InteractiveTarget(targetRef, "button", name, null, true, true, false);
  }
}
