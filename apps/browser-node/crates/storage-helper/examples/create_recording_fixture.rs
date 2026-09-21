use bytes::Bytes;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::path::PathBuf;
use std::time::Duration;
use storage_helper::object_archive::{ObjectArchive, ProfileArchiveCrypto, S3ArchiveConfig};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    anyhow::ensure!(
        arguments.len() == 9,
        "usage: create_recording_fixture <endpoint> <bucket> <access-key> <secret-key> <keyring> <tenant> <profile> <session> <recording>"
    );
    let archive = ObjectArchive::s3(S3ArchiveConfig {
        endpoint: arguments[0].clone(),
        bucket: arguments[1].clone(),
        access_key_id: arguments[2].clone(),
        secret_access_key: arguments[3].clone(),
        region: "us-east-1".to_owned(),
        prefix: String::new(),
        connect_timeout: Duration::from_secs(1),
        operation_timeout: Duration::from_secs(3),
        allow_http: true,
        profile_crypto: ProfileArchiveCrypto::from_keyring_file(&PathBuf::from(&arguments[4]))?,
    })?;
    let tenant_id = &arguments[5];
    let profile_id = &arguments[6];
    let session_id = &arguments[7];
    let recording_id = &arguments[8];
    let mut first_segment_sha256 = String::new();
    for sequence in 0_u64..25 {
        let captured_at_ms = sequence + 1;
        let content = Bytes::from(
            json!({
                "capturedAtMs": captured_at_ms,
                "cdpSessionId": 7,
                "format": "jpeg",
                "redactionState": "MASKED",
                "redactedRegionCount": 1,
                "redactionPolicyVersion": 1,
                "data": "/9j/"
            })
            .to_string(),
        );
        let content_sha256 = format!("{:x}", Sha256::digest(&content));
        if sequence == 0 {
            first_segment_sha256.clone_from(&content_sha256);
        }
        archive
            .commit_recording_segment(
                tenant_id,
                profile_id,
                session_id,
                recording_id,
                sequence,
                content,
                &content_sha256,
                1,
                1,
                1,
                1,
                captured_at_ms,
                captured_at_ms,
            )
            .await?;
    }
    let completed = archive
        .complete_recording(
            tenant_id,
            profile_id,
            session_id,
            recording_id,
            25,
            25,
            25,
            25,
            1,
            1,
            25,
        )
        .await?;
    println!(
        "{}",
        json!({
            "manifestObjectKey": completed.object_key,
            "manifestSha256": completed.manifest_sha256,
            "manifestBytes": completed.manifest_bytes,
            "firstSegmentSha256": first_segment_sha256
        })
    );
    Ok(())
}
