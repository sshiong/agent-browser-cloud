package io.browsercloud.application;

import static io.browsercloud.api.SessionRecordingModels.*;
import static io.browsercloud.application.SessionRecordingPlaybackNodeGateway.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionRecordingPlaybackApplicationServiceTest {

  private SessionRepository sessions;
  private SessionRecordingPlaybackStore store;
  private BrowserCapacityApplicationService capacity;
  private SessionRecordingPlaybackNodeGateway nodeAccess;
  private AuditApplicationService audit;
  private SessionRecordingPlaybackApplicationService service;

  @BeforeEach
  void setUp() {
    sessions = mock(SessionRepository.class);
    store = mock(SessionRecordingPlaybackStore.class);
    capacity = mock(BrowserCapacityApplicationService.class);
    nodeAccess = mock(SessionRecordingPlaybackNodeGateway.class);
    audit = mock(AuditApplicationService.class);
    service =
        new SessionRecordingPlaybackApplicationService(
            sessions, store, capacity, nodeAccess, audit);
  }

  @Test
  void createsPurposeActorAndRecordingBoundGrantWithoutStorageCoordinates() {
    var session = runningSession();
    var now = Instant.parse("2026-09-21T08:00:00Z");
    var recording = recording(now);
    var view =
        new RecordingPlaybackGrantView(
            "rgr_0123456789abcdef0123456789abcdef",
            session.sessionId(),
            recording.recordingId(),
            RecordingPlaybackPurpose.SECURITY_INVESTIGATION,
            "ISSUED",
            now.plusSeconds(300),
            now,
            null,
            null,
            "request-test");
    when(sessions.require(session.sessionId())).thenReturn(session);
    when(store.findAvailableRecording(
            eq("tenant-test"), eq(session.sessionId()), eq(recording.recordingId()), any()))
        .thenReturn(Optional.of(recording));
    when(store.findGrantByIdempotency("tenant-test", "actor-test", "playback-key"))
        .thenReturn(Optional.empty(), Optional.of(view));
    when(store.insertGrant(
            any(),
            eq("tenant-test"),
            eq(session.sessionId()),
            eq(recording.recordingId()),
            eq("actor-test"),
            eq(RecordingPlaybackPurpose.SECURITY_INVESTIGATION),
            eq("playback-key"),
            eq("request-test"),
            any(),
            any()))
        .thenReturn(true);

    var result =
        service.create(
            session.sessionId(),
            recording.recordingId(),
            "tenant-test",
            "actor-test",
            "playback-key",
            "request-test",
            new CreateRecordingPlaybackGrantRequest(
                RecordingPlaybackPurpose.SECURITY_INVESTIGATION));

    assertThat(result).isEqualTo(view);
    verify(store)
        .insertGrant(
            any(),
            eq("tenant-test"),
            eq(session.sessionId()),
            eq(recording.recordingId()),
            eq("actor-test"),
            eq(RecordingPlaybackPurpose.SECURITY_INVESTIGATION),
            eq("playback-key"),
            eq("request-test"),
            any(),
            any());
    verify(audit).append(any());
  }

  @Test
  void redeemsExactlyOneBoundRecordingPageAndPersistsNoSignedUrls() {
    var session = runningSession();
    var now = Instant.now();
    var claim = claim(now);
    var signed = signed(claim, now, 2L);
    when(sessions.require(session.sessionId())).thenReturn(session);
    when(store.claim(
            eq("tenant-test"),
            eq(session.sessionId()),
            eq(claim.grantId()),
            eq("actor-test"),
            any()))
        .thenReturn(claim);
    when(capacity.nodeHasCapability(session.nodeId(), "recordingPlayback", "presigned-segments-v1"))
        .thenReturn(true);
    when(nodeAccess.sign(any())).thenReturn(signed);

    var result =
        service.redeem(
            session.sessionId(), claim.grantId(), "tenant-test", "actor-test", "request-test");

    assertThat(result.segments()).hasSize(2);
    assertThat(result.segments().getFirst().downloadUrl()).contains("signature=ephemeral");
    var request = ArgumentCaptor.forClass(SignRecordingPlaybackRequest.class);
    verify(nodeAccess).sign(request.capture());
    assertThat(request.getValue().segmentOffset()).isZero();
    assertThat(request.getValue().segmentLimit()).isEqualTo(24);
    assertThat(request.getValue().expiresInSeconds()).isEqualTo(60);
    assertThat(request.getValue().manifestSha256()).isEqualTo(claim.manifestSha256());
    verify(store).commitGrant(eq(claim.grantId()), eq(session.nodeId()), any(), any());
    verify(store, never()).failGrant(any(), any(), any());
    verify(audit).append(any());
  }

  @Test
  void failedIntegritySigningBurnsTheOneTimeGrantAndAuditsFailure() {
    var session = runningSession();
    var claim = claim(Instant.now());
    when(sessions.require(session.sessionId())).thenReturn(session);
    when(store.claim(any(), any(), any(), any(), any())).thenReturn(claim);
    when(capacity.nodeHasCapability(session.nodeId(), "recordingPlayback", "presigned-segments-v1"))
        .thenReturn(true);
    when(nodeAccess.sign(any()))
        .thenThrow(new RecordingPlaybackNodeRejectedException("recording marker mismatch"));

    assertThatThrownBy(
            () ->
                service.redeem(
                    session.sessionId(),
                    claim.grantId(),
                    "tenant-test",
                    "actor-test",
                    "request-test"))
        .isInstanceOf(RecordingPlaybackNodeRejectedException.class);

    verify(store).failGrant(eq(claim.grantId()), eq("RECORDING_PLAYBACK_OBJECT_REJECTED"), any());
    verify(store, never()).commitGrant(any(), any(), any(), any());
    verify(audit).append(any());
  }

  private static SessionRecordingPlaybackStore.RecordingRecord recording(Instant now) {
    return new SessionRecordingPlaybackStore.RecordingRecord(
        "rec_0123456789abcdef0123456789abcdef",
        "node-test",
        "profile-test",
        "a".repeat(64),
        1024,
        2,
        9,
        4,
        7,
        1,
        now.minusSeconds(20),
        now.minusSeconds(10));
  }

  private static SessionRecordingPlaybackStore.RecordingPlaybackClaim claim(Instant now) {
    var recording = recording(now);
    return new SessionRecordingPlaybackStore.RecordingPlaybackClaim(
        "rgr_0123456789abcdef0123456789abcdef",
        recording.recordingId(),
        recording.nodeId(),
        recording.profileId(),
        recording.manifestSha256(),
        recording.manifestBytes(),
        recording.segmentCount(),
        recording.frameCount(),
        recording.redactedFrameCount(),
        recording.redactedRegionCount(),
        recording.redactionPolicyVersion(),
        recording.startedAt(),
        recording.endedAt());
  }

  private static SignedRecordingPlayback signed(
      SessionRecordingPlaybackStore.RecordingPlaybackClaim claim, Instant now, Long nextOffset) {
    return new SignedRecordingPlayback(
        claim.grantId(),
        claim.nodeId(),
        claim.recordingId(),
        claim.manifestSha256(),
        claim.frameCount(),
        claim.redactedFrameCount(),
        claim.redactedRegionCount(),
        claim.redactionPolicyVersion(),
        now.plusSeconds(60),
        nextOffset,
        List.of(
            new SignedRecordingSegment(
                0, "b".repeat(64), 512, 5, 1, 2, "https://objects.test/0?signature=ephemeral"),
            new SignedRecordingSegment(
                1, "c".repeat(64), 512, 4, 3, 4, "https://objects.test/1?signature=ephemeral")));
  }

  private static SessionContext runningSession() {
    var now = Instant.parse("2026-09-21T07:00:00Z");
    return new SessionContext(
        "ses_1234567890abcdef",
        "tenant-test",
        "profile-test",
        "node-test",
        "runtime-stable",
        "isolation-standard",
        "proxy-test",
        1,
        1,
        1,
        1,
        ResourceClass.L2,
        SessionState.RUNNING,
        "policy-hash",
        now,
        now);
  }
}
