use super::*;
use helper_contracts::{read_frame, write_frame, StorageCommand, StorageRequest, SCHEMA_VERSION};
use tokio::net::UnixListener;

fn pending() -> PendingRecording {
    PendingRecording {
        recording_id: "rec_owned".into(),
        segment_count: 1,
        frame_count: 1,
        dropped_frames: 0,
        redacted_frame_count: 1,
        redacted_region_count: 3,
        started_at_ms: 1,
        ended_at_ms: 2,
    }
}

#[tokio::test]
async fn writer_completion_waits_for_capture_cleanup_before_finalization_is_possible() {
    let (stop, mut stopped) = watch::channel(false);
    let (release, wait) = oneshot::channel();
    let capture = tokio::spawn(async move {
        stopped.changed().await.unwrap();
        assert!(*stopped.borrow());
        wait.await.unwrap();
        Ok(())
    });
    let writer = tokio::spawn(async { Ok(pending()) });
    let mut joined = tokio::spawn(join_recording_producers(capture, writer, stop));
    assert!(tokio::time::timeout(Duration::from_millis(50), &mut joined)
        .await
        .is_err());
    release.send(()).unwrap();
    assert_eq!(joined.await.unwrap().unwrap().recording_id, "rec_owned");
}

#[tokio::test]
async fn panicked_writer_still_stops_and_joins_capture() {
    let (stop, mut stopped) = watch::channel(false);
    let cleaned = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let observed = Arc::clone(&cleaned);
    let capture = tokio::spawn(async move {
        stopped.changed().await.unwrap();
        observed.store(true, std::sync::atomic::Ordering::Release);
        Ok(())
    });
    let writer = tokio::spawn(async {
        panic!("owned writer failure");
        #[allow(unreachable_code)]
        Ok(pending())
    });
    assert!(join_recording_producers(capture, writer, stop)
        .await
        .is_err());
    assert!(cleaned.load(std::sync::atomic::Ordering::Acquire));
}

async fn fixture() -> (PathBuf, RecordingSpec, UnixListener) {
    let root = std::env::temp_dir().join(format!("rh-{}", uuid::Uuid::new_v4().simple()));
    tokio::fs::create_dir_all(&root).await.unwrap();
    let scope = helper_client::RecordingUploadHoldScope {
        operation_id: "rua_owned".into(),
        authority_epoch: 1,
        placement_epoch: 1,
        tenant_id: "tenant-owned".into(),
        profile_id: "profile-owned".into(),
        session_id: "ses_owned".into(),
        recording_id: "rec_owned".into(),
    };
    let workspace_root =
        root.join("tenants/tenant-owned/profiles/profile-owned/workspaces/ses_owned");
    let ephemeral = workspace_root.join("ephemeral");
    tokio::fs::create_dir_all(ephemeral.join("recordings/rec_owned"))
        .await
        .unwrap();
    let journal = Arc::new(
        node_journal::SqliteNodeJournal::open(root.join("journal.sqlite"))
            .await
            .unwrap(),
    );
    let socket = root.join("s");
    let listener = UnixListener::bind(&socket).unwrap();
    let spec = RecordingSpec {
        session_id: scope.session_id.clone(),
        cdp_endpoint: "http://127.0.0.1:1".into(),
        workspace: StorageWorkspace {
            tenant_id: scope.tenant_id.clone(),
            profile_id: scope.profile_id.clone(),
            session_id: scope.session_id.clone(),
            core_dir: workspace_root.join("core").to_string_lossy().into_owned(),
            ephemeral_dir: ephemeral.to_string_lossy().into_owned(),
            profile_write_epoch: 1,
            restored_checkpoint_id: None,
            restore_status: helper_client::StorageRestoreStatus::Empty,
        },
        storage_helper: Arc::new(
            StorageHelperClient::new(socket, Duration::from_secs(1), root.clone()).unwrap(),
        ),
        privacy_scanner_path: root.join("unused-scanner"),
        upload_hold: Some(RecordingUploadContext { scope, journal }),
    };
    (root, spec, listener)
}

#[tokio::test]
async fn held_segment_persists_receipt_before_ack_and_retains_source_on_ack_failure() {
    let (root, spec, listener) = fixture().await;
    let context = spec.upload_hold.clone().unwrap();
    let server_journal = Arc::clone(&context.journal);
    let server_scope = context.scope.clone();
    let server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let request: StorageRequest = read_frame(&mut stream).await.unwrap();
        let StorageCommand::CommitRecordingHeldSegment { scope, segment } = request.command else {
            panic!("held segment downgraded");
        };
        assert_eq!(scope, server_scope);
        let proof = helper_client::StorageRecordingUploadHoldProof {
            operation_id: scope.operation_id.clone(),
            authority_epoch: 1,
            placement_epoch: 1,
            scope_sha256: "a".repeat(64),
            proof_hash: "b".repeat(64),
            object_version_count: 2,
        };
        write_frame(&mut stream,&json!({"schemaVersion":SCHEMA_VERSION,"requestId":request.request_id,"ok":true,
            "recording":{"recordingId":scope.recording_id,"segmentSequence":segment.sequence,"contentBytes":segment.content_bytes,
                "frameCount":segment.frame_count,"redactedFrameCount":segment.redacted_frame_count,
                "redactedRegionCount":segment.redacted_region_count,"redactionPolicyVersion":2,"completed":false,"uploadHold":proof}})).await.unwrap();
        let (mut stream, _) = listener.accept().await.unwrap();
        let request: StorageRequest = read_frame(&mut stream).await.unwrap();
        assert!(matches!(
            request.command,
            StorageCommand::AcknowledgeRecordingHeldSegment { .. }
        ));
        assert_eq!(
            server_journal
                .recording_upload_scope_hash(&server_scope)
                .await
                .unwrap(),
            "a".repeat(64)
        );
        write_frame(&mut stream,&json!({"schemaVersion":SCHEMA_VERSION,"requestId":request.request_id,"ok":false,"errorCode":"OWNED_ACK_FAILURE"})).await.unwrap();
        assert!(
            tokio::time::timeout(Duration::from_millis(40), listener.accept())
                .await
                .is_err()
        );
    });
    let directory = PathBuf::from(&spec.workspace.ephemeral_dir).join("recordings/rec_owned");
    let mut segment = SegmentWriter::open(&directory, 0, 1).await.unwrap();
    segment.file.write_all(b"owned staged bytes").await.unwrap();
    segment.hasher.update(b"owned staged bytes");
    segment.bytes = 18;
    segment.frames = 1;
    segment.redacted_frames = 1;
    segment.redacted_regions = 3;
    segment.ended_at_ms = 2;
    assert!(commit_segment(&spec, "rec_owned", &mut segment)
        .await
        .is_err());
    assert!(directory
        .join("segment-00000000000000000000.ndjson")
        .exists());
    assert_eq!(
        context
            .journal
            .recording_upload_scope_hash(&context.scope)
            .await
            .unwrap(),
        "a".repeat(64)
    );
    server.await.unwrap();
    drop(segment);
    drop(spec);
    drop(context);
    tokio::fs::remove_dir_all(root).await.unwrap();
}

#[tokio::test]
async fn held_finalization_prepares_before_rpc_and_commits_result_before_summary() {
    let (root, spec, listener) = fixture().await;
    let context = spec.upload_hold.clone().unwrap();
    let receipt = helper_client::RecordingUploadReceipt {
        schema_version: 1,
        scope: context.scope.clone(),
        segment: helper_client::StorageRecordingSegmentDescriptor {
            sequence: 0,
            content_sha256: "f".repeat(64),
            content_bytes: 18,
            frame_count: 1,
            redacted_frame_count: 1,
            redacted_region_count: 3,
            redaction_policy_version: 2,
            started_at_ms: 1,
            ended_at_ms: 2,
        },
        proof: helper_client::StorageRecordingUploadHoldProof {
            operation_id: context.scope.operation_id.clone(),
            authority_epoch: 1,
            placement_epoch: 1,
            scope_sha256: "a".repeat(64),
            proof_hash: "b".repeat(64),
            object_version_count: 2,
        },
    };
    context
        .journal
        .record_recording_upload_receipt(&receipt)
        .await
        .unwrap();
    let journal = Arc::clone(&context.journal);
    let server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let request: StorageRequest = read_frame(&mut stream).await.unwrap();
        let StorageCommand::SealRecordingHeldUpload {
            scope,
            finalization: f,
            expected_scope_sha256,
        } = request.command
        else {
            panic!("seal downgraded");
        };
        let prepared = journal
            .recording_upload_seal_request(&scope.tenant_id, &scope.session_id, &scope.recording_id)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(prepared.finalization, f);
        assert_eq!(prepared.scope, scope);
        assert_eq!(prepared.expected_scope_sha256, expected_scope_sha256);
        write_frame(&mut stream,&json!({"schemaVersion":SCHEMA_VERSION,"requestId":request.request_id,"ok":true,
            "recording":{"recordingId":scope.recording_id,"contentBytes":0,"frameCount":1,"redactedFrameCount":1,
                "redactedRegionCount":3,"redactionPolicyVersion":2,"completed":true,"manifestSha256":"b".repeat(64),"manifestBytes":100,
                "objectKey":"prefix/tenants/tenant-owned/profiles/profile-owned/sessions/ses_owned/recordings/rec_owned/COMMITTED",
                "uploadSeal":{"operationId":scope.operation_id,"authorityEpoch":1,"placementEpoch":1,
                    "proof":{"protocol_version":2,"scope_sha256":expected_scope_sha256,"manifest_sha256":"b".repeat(64),"manifest_bytes":100,
                        "segment_count":1,"object_version_count":3,"inventory_sha256":"c".repeat(64),"proof_hash":"d".repeat(64),"completed_at_ms":3}}}})).await.unwrap();
    });
    let summary = finalize_recording(&spec, pending()).await.unwrap();
    assert_eq!(summary.manifest_sha256, "b".repeat(64));
    assert!(context
        .journal
        .recording_upload_seal(
            &context.scope.tenant_id,
            &context.scope.session_id,
            &context.scope.recording_id
        )
        .await
        .unwrap()
        .is_some());
    server.await.unwrap();
    drop(spec);
    drop(context);
    tokio::fs::remove_dir_all(root).await.unwrap();
}
