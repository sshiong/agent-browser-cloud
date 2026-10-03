package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.persistence.BrowserStateEntity;
import io.browsercloud.proto.node.v1.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RegionalStabilityProjectionTest {
  private final NodeEventMapper mapper = new NodeEventMapper();
  private static final String ELEMENT = "e" + "a".repeat(24);

  private BrowserStateEvent.Builder state() {
    var bounds = TargetBounds.newBuilder().setX(10).setY(20).setWidth(100).setHeight(30);
    return BrowserStateEvent.newBuilder()
        .setSessionId("ses_region")
        .setStateVersion(7)
        .setTargetRevision(3)
        .setUrl("https://example.test/regions")
        .setTitle("Region fixture")
        .setContentHash("a".repeat(64))
        .setStateQuality("COMPLETE")
        .setDocumentReadyState("complete")
        .setNetworkEvidenceFresh(true)
        .setNativeDialogEvidenceFresh(true)
        .setOpaqueFrameEvidenceFresh(true)
        .addTabs(
            BrowserTabState.newBuilder()
                .setTabId("tab-a")
                .setActive(true)
                .setUrl("https://example.test/regions")
                .setTitle("Region fixture"))
        .setActiveTabId("tab-a")
        .setPageStability(
            PageStabilityState.newBuilder()
                .setEvidenceFresh(true)
                .setFocusQuietMillis(2000)
                .setRouteQuietMillis(2000))
        .addTargets(
            InteractiveTargetState.newBuilder()
                .setTargetRef("target:3:region")
                .setElementId(ELEMENT)
                .setRole("button")
                .setName("Save")
                .setBounds(bounds)
                .setEnabled(true)
                .setVisible(true)
                .setInteractive(true)
                .setInViewport(true)
                .setFrameId("main"))
        .setRegionalStability(
            RegionalStabilityState.newBuilder()
                .setEvidenceFresh(true)
                .setMaxWaitReached(true)
                .setChangingMillis(15000)
                .setTransactionFree(true)
                .addStableRegions(
                    StableTargetRegionState.newBuilder()
                        .setElementId(ELEMENT)
                        .setBounds(bounds)
                        .setQuietMillis(2000)
                        .setConsecutiveSamples(3))
                .addUnstableRegions(
                    UnstableTargetRegionState.newBuilder()
                        .setReason("OUTSIDE_PROVEN_TARGET_REGIONS")));
  }

  private NodeEvent.StateUpdated mapped(BrowserStateEvent.Builder payload) {
    return (NodeEvent.StateUpdated)
        mapper
            .toCommand(
                EventEnvelope.newBuilder()
                    .setEventId("evt-region")
                    .setEventType(NodeEventMapper.BROWSER_STATE_UPDATED)
                    .setTenantId("tenant-test")
                    .setSessionId("ses_region")
                    .setContextEpoch(2)
                    .setSequence(1)
                    .setPayload(payload.build().toByteString())
                    .build())
            .event();
  }

  private NodeEvent.StateDiff mappedDiff(BrowserStateDiffEvent.Builder payload) {
    return (NodeEvent.StateDiff)
        mapper
            .toCommand(
                EventEnvelope.newBuilder()
                    .setEventId("evt-region-diff")
                    .setEventType(NodeEventMapper.BROWSER_STATE_DIFF)
                    .setTenantId("tenant-test")
                    .setSessionId("ses_region")
                    .setContextEpoch(2)
                    .setSequence(2)
                    .setPayload(payload.build().toByteString())
                    .build())
            .event();
  }

  private BrowserStateDiffEvent.Builder diff(long base, long version) {
    var full = state().build();
    return BrowserStateDiffEvent.newBuilder()
        .setSessionId(full.getSessionId())
        .setBaseStateVersion(base)
        .setStateVersion(version)
        .setTargetRevision(full.getTargetRevision())
        .setUrl(full.getUrl())
        .setTitle(full.getTitle())
        .setContentHash("b".repeat(64))
        .setStateQuality(full.getStateQuality())
        .setDocumentReadyState(full.getDocumentReadyState())
        .setNetworkEvidenceFresh(true)
        .addAllTabs(full.getTabsList())
        .setActiveTabId(full.getActiveTabId())
        .setNativeDialogEvidenceFresh(true)
        .setOpaqueFrameEvidenceFresh(true)
        .setPageStability(full.getPageStability())
        .setRegionalStability(full.getRegionalStability());
  }

  @Test
  void shouldBindDiffRegionsToMergedTargetsAndClearMissingOrPartialProof() {
    var jpa = mock(BrowserStateJpaRepository.class);
    var stored = new AtomicReference<BrowserStateEntity>();
    when(jpa.findById("ses_region")).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
    when(jpa.findByIdForUpdate("ses_region"))
        .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
    when(jpa.save(any(BrowserStateEntity.class)))
        .thenAnswer(
            invocation -> {
              var entity = invocation.getArgument(0, BrowserStateEntity.class);
              stored.set(entity);
              return entity;
            });
    var repository =
        new JpaBrowserStateRepository(jpa, new ObjectMapper().findAndRegisterModules());
    repository.save("tenant-test", 2, mapped(state()));
    var periodic = repository.applyDiff("tenant-test", 2, mappedDiff(diff(7, 8))).orElseThrow();
    assertThat(periodic.regionalStability().stableRegions()).hasSize(1);
    assertThat(periodic.targets()).hasSize(1);
    assertThat(repository.applyDiff("other-tenant", 2, mappedDiff(diff(8, 9)))).isEmpty();
    assertThat(repository.applyDiff("tenant-test", 3, mappedDiff(diff(8, 9)))).isEmpty();
    var orphan = mappedDiff(diff(8, 9).addRemovedTargetRefs("target:3:region"));
    assertThatThrownBy(() -> repository.applyDiff("tenant-test", 2, orphan))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(repository.find("ses_region").orElseThrow().state().stateVersion()).isEqualTo(8);
    var partial =
        mappedDiff(
            diff(8, 9)
                .setSnapshotKind("REGION_RESYNC")
                .setRequestedRootRef("html>body")
                .setResyncRequestId("cmd_abcdefghijklmnop"));
    assertThat(repository.applyDiff("tenant-test", 2, partial).orElseThrow().regionalStability())
        .isEqualTo(NodeEvent.RegionalStability.unknown());
    assertThat(
            repository
                .applyDiff("tenant-test", 2, mappedDiff(diff(9, 10).clearRegionalStability()))
                .orElseThrow()
                .regionalStability())
        .isEqualTo(NodeEvent.RegionalStability.unknown());
  }

  @Test
  void shouldRoundTripExactBoundedEvidenceThroughProtoAndAuthoritativeJson() throws Exception {
    var event = mapped(state());
    var objectMapper = new ObjectMapper().findAndRegisterModules();
    var json = objectMapper.writeValueAsString(event);
    var restored = objectMapper.readValue(json, NodeEvent.StateUpdated.class);
    assertThat(restored.regionalStability()).isEqualTo(event.regionalStability());
    assertThat(restored.regionalStability().stableRegions()).hasSize(1);
    assertThat(restored.regionalStability().stableRegions().getFirst().bounds())
        .isEqualTo(restored.targets().getFirst().bounds());
    assertThat(restored.networkQuietMillis()).isZero();
    assertThat(restored.pageStability().domQuietMillis()).isZero();
  }

  @Test
  void shouldDefaultLegacyAndNonFullStatesToUnknown() throws Exception {
    assertThat(mapped(state().clearRegionalStability()).regionalStability())
        .isEqualTo(NodeEvent.RegionalStability.unknown());
    var objectMapper = new ObjectMapper().findAndRegisterModules();
    var json = objectMapper.valueToTree(mapped(state()));
    ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("regionalStability");
    assertThat(objectMapper.treeToValue(json, NodeEvent.StateUpdated.class).regionalStability())
        .isEqualTo(NodeEvent.RegionalStability.unknown());
    for (String kind : new String[] {"quality", "document", "network", "component", "partial"}) {
      var payload = state();
      switch (kind) {
        case "quality" -> payload.setStateQuality("DEPTH_LIMITED");
        case "document" -> payload.setDocumentReadyState("interactive");
        case "network" -> payload.setNetworkEvidenceFresh(false);
        case "component" -> payload.clearPageStability();
        case "partial" -> payload.setSnapshotKind("REGION_RESYNC");
        default -> throw new AssertionError();
      }
      assertThat(mapped(payload).regionalStability())
          .isEqualTo(NodeEvent.RegionalStability.unknown());
    }
    for (String kind : new String[] {"missing-tab", "wrong-tab", "ambiguous-tab"}) {
      var persisted =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              objectMapper.valueToTree(mapped(state()));
      switch (kind) {
        case "missing-tab" -> persisted.putArray("tabs");
        case "wrong-tab" -> persisted.put("activeTabId", "tab-missing");
        case "ambiguous-tab" ->
            ((com.fasterxml.jackson.databind.node.ArrayNode) persisted.get("tabs"))
                .addObject()
                .put("tabId", "tab-other")
                .put("active", true);
        default -> throw new AssertionError();
      }
      assertThat(
              objectMapper.treeToValue(persisted, NodeEvent.StateUpdated.class).regionalStability())
          .isEqualTo(NodeEvent.RegionalStability.unknown());
    }
  }

  @Test
  void shouldRejectUnboundOrMalformedRegionEvidenceBeforePublication() {
    for (String kind :
        new String[] {
          "missing",
          "duplicate",
          "geometry",
          "occluded",
          "frame",
          "samples",
          "quiet",
          "maxwait",
          "budget",
          "reason"
        }) {
      var payload = state();
      var proof = payload.getRegionalStability().toBuilder();
      switch (kind) {
        case "missing" -> payload.clearTargets();
        case "duplicate" -> payload.addTargets(payload.getTargets(0));
        case "geometry" ->
            payload
                .getTargetsBuilder(0)
                .setBounds(TargetBounds.newBuilder().setX(11).setY(20).setWidth(100).setHeight(30));
        case "occluded" -> payload.getTargetsBuilder(0).setOccluded(true);
        case "frame" -> payload.getTargetsBuilder(0).setFrameId("child-frame");
        case "samples" -> proof.getStableRegionsBuilder(0).setConsecutiveSamples(2);
        case "quiet" -> proof.getStableRegionsBuilder(0).setQuietMillis(-1);
        case "maxwait" -> proof.setChangingMillis(14999);
        case "budget" -> {
          for (int index = 0; index < 41; index++)
            proof.addStableRegions(proof.getStableRegions(0));
        }
        case "reason" ->
            proof.getUnstableRegionsBuilder(0).setReason("private-untrusted-page-text");
        default -> throw new AssertionError();
      }
      payload.setRegionalStability(proof);
      assertThatThrownBy(() -> mapped(payload))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining("private-untrusted");
    }
  }

  @Test
  void shouldPersistEvidenceAndClearItDuringResyncAndInvalidation() {
    var jpa = mock(BrowserStateJpaRepository.class);
    var stored = new AtomicReference<BrowserStateEntity>();
    when(jpa.findById("ses_region")).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
    when(jpa.save(any(BrowserStateEntity.class)))
        .thenAnswer(
            invocation -> {
              var entity = invocation.getArgument(0, BrowserStateEntity.class);
              stored.set(entity);
              return entity;
            });
    var repository =
        new JpaBrowserStateRepository(jpa, new ObjectMapper().findAndRegisterModules());
    repository.save("tenant-test", 2, mapped(state()));
    assertThat(
            repository.find("ses_region").orElseThrow().state().regionalStability().evidenceFresh())
        .isTrue();
    repository.markResyncing("tenant-test", 2, "ses_region");
    assertThat(repository.find("ses_region").orElseThrow().state().regionalStability())
        .isEqualTo(NodeEvent.RegionalStability.unknown());
    repository.save("tenant-test", 2, mapped(state().setStateVersion(8)));
    assertThat(
            repository.find("ses_region").orElseThrow().state().regionalStability().evidenceFresh())
        .isTrue();
    repository.invalidate("tenant-test", 2, "ses_region", 9, "owned-fixture");
    assertThat(repository.find("ses_region").orElseThrow().state().regionalStability())
        .isEqualTo(NodeEvent.RegionalStability.unknown());
  }
}
