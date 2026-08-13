use crate::{LocalProfileStore, ProfileCheckpointManifest};
use anyhow::Context;
use bytes::Bytes;
use futures_util::StreamExt;
use http::Method;
use object_store::aws::{AmazonS3, AmazonS3Builder};
use object_store::path::Path;
use object_store::signer::Signer;
use object_store::{ClientOptions, ObjectStoreExt, PutPayload};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[derive(Clone)]
pub struct ObjectArchive {
    store: Arc<AmazonS3>,
    prefix: String,
    operation_timeout: Duration,
}

pub struct EvidenceDownloadRequest<'a> {
    pub tenant_id: &'a str,
    pub profile_id: &'a str,
    pub session_id: &'a str,
    pub evidence_id: &'a str,
    pub content_sha256: &'a str,
    pub content_bytes: u64,
    pub expires_in: Duration,
}

pub struct ProfileExportDownloadRequest<'a> {
    pub tenant_id: &'a str,
    pub profile_id: &'a str,
    pub checkpoint_id: &'a str,
    pub expires_in: Duration,
}

pub struct SignedProfileExport {
    pub archive_sha256: String,
    pub archive_size_bytes: u64,
    pub download_url: String,
    pub expires_at_ms: u64,
}

const MAX_PROFILE_EXPORT_BYTES: usize = 256 * 1024 * 1024;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ArchiveCommitMarker<'a> {
    checkpoint_id: &'a str,
    checkpoint_epoch: u64,
    profile_write_epoch: u64,
    content_hash: &'a str,
    archive_sha256: String,
    archive_bytes: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredArchiveCommitMarker {
    checkpoint_id: String,
    archive_sha256: String,
    archive_bytes: usize,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RecordingSegmentMarker<'a> {
    recording_id: &'a str,
    segment_sequence: u64,
    content_sha256: &'a str,
    content_bytes: u64,
    frame_count: u64,
    started_at_ms: u64,
    ended_at_ms: u64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RecordingCommitMarker<'a> {
    recording_id: &'a str,
    segment_count: u64,
    frame_count: u64,
    started_at_ms: u64,
    ended_at_ms: u64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct EvidenceCommitMarker<'a> {
    evidence_id: &'a str,
    evidence_kind: &'a str,
    content_sha256: &'a str,
    content_bytes: u64,
    captured_at_ms: u64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredEvidenceCommitMarker {
    evidence_id: String,
    content_sha256: String,
    content_bytes: u64,
}

impl ObjectArchive {
    pub fn s3(config: S3ArchiveConfig) -> anyhow::Result<Self> {
        anyhow::ensure!(
            config.operation_timeout >= Duration::from_millis(100),
            "object storage timeout must be at least 100ms"
        );
        let client_options = ClientOptions::new()
            .with_timeout(config.operation_timeout)
            .with_connect_timeout(config.connect_timeout)
            .with_allow_http(config.allow_http);
        let store = AmazonS3Builder::new()
            .with_bucket_name(config.bucket)
            .with_region(config.region)
            .with_endpoint(config.endpoint)
            .with_access_key_id(config.access_key_id)
            .with_secret_access_key(config.secret_access_key)
            .with_allow_http(config.allow_http)
            .with_client_options(client_options)
            .build()
            .context("build S3-compatible Object Storage client")?;
        Ok(Self {
            store: Arc::new(store),
            prefix: config.prefix.trim_matches('/').to_owned(),
            operation_timeout: config.operation_timeout,
        })
    }

    pub async fn commit_checkpoint(
        &self,
        local: &LocalProfileStore,
        manifest: &ProfileCheckpointManifest,
    ) -> anyhow::Result<()> {
        let archive = local.pack_checkpoint(manifest).await?;
        let archive_hash = hex_sha256(&archive);
        let base = self.object_key(manifest);
        self.put(
            &format!("{base}/checkpoint.tar.zst"),
            Bytes::from(archive.clone()),
        )
        .await?;
        self.put(
            &format!("{base}/manifest.json"),
            Bytes::from(serde_json::to_vec(manifest)?),
        )
        .await?;
        let marker = ArchiveCommitMarker {
            checkpoint_id: &manifest.checkpoint_id,
            checkpoint_epoch: manifest.checkpoint_epoch,
            profile_write_epoch: manifest.profile_write_epoch,
            content_hash: &manifest.content_hash,
            archive_sha256: archive_hash,
            archive_bytes: archive.len(),
        };
        self.put(
            &format!("{base}/COMMITTED"),
            Bytes::from(serde_json::to_vec(&marker)?),
        )
        .await
    }

    pub async fn restore_checkpoint(
        &self,
        local: &LocalProfileStore,
        tenant_id: &str,
        profile_id: &str,
        checkpoint_id: &str,
    ) -> anyhow::Result<ProfileCheckpointManifest> {
        let base = self.object_key_for(tenant_id, profile_id, checkpoint_id);
        let marker_bytes = self.get(&format!("{base}/COMMITTED")).await?;
        let marker: StoredArchiveCommitMarker = serde_json::from_slice(&marker_bytes)?;
        anyhow::ensure!(
            marker.checkpoint_id == checkpoint_id,
            "archive commit marker checkpoint mismatch"
        );
        let archive = self.get(&format!("{base}/checkpoint.tar.zst")).await?;
        anyhow::ensure!(
            archive.len() == marker.archive_bytes && hex_sha256(&archive) == marker.archive_sha256,
            "checkpoint archive integrity verification failed"
        );
        local
            .install_checkpoint_archive(tenant_id, profile_id, checkpoint_id, archive.to_vec())
            .await
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn commit_recording_segment(
        &self,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
        recording_id: &str,
        segment_sequence: u64,
        content: Bytes,
        content_sha256: &str,
        frame_count: u64,
        started_at_ms: u64,
        ended_at_ms: u64,
    ) -> anyhow::Result<String> {
        anyhow::ensure!(
            hex_sha256(&content) == content_sha256,
            "recording segment integrity verification failed"
        );
        let base = self.recording_key_for(tenant_id, profile_id, session_id, recording_id);
        let object_key = format!("{base}/segments/{segment_sequence:020}.ndjson");
        self.put(&object_key, content.clone()).await?;
        let marker = RecordingSegmentMarker {
            recording_id,
            segment_sequence,
            content_sha256,
            content_bytes: content.len() as u64,
            frame_count,
            started_at_ms,
            ended_at_ms,
        };
        self.put(
            &format!("{base}/segments/{segment_sequence:020}.COMMITTED"),
            Bytes::from(serde_json::to_vec(&marker)?),
        )
        .await?;
        Ok(object_key)
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn complete_recording(
        &self,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
        recording_id: &str,
        segment_count: u64,
        frame_count: u64,
        started_at_ms: u64,
        ended_at_ms: u64,
    ) -> anyhow::Result<String> {
        let base = self.recording_key_for(tenant_id, profile_id, session_id, recording_id);
        let marker = RecordingCommitMarker {
            recording_id,
            segment_count,
            frame_count,
            started_at_ms,
            ended_at_ms,
        };
        let object_key = format!("{base}/COMMITTED");
        self.put(&object_key, Bytes::from(serde_json::to_vec(&marker)?))
            .await?;
        Ok(object_key)
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn commit_evidence(
        &self,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
        evidence_id: &str,
        evidence_kind: &str,
        content: Bytes,
        content_sha256: &str,
        captured_at_ms: u64,
    ) -> anyhow::Result<String> {
        anyhow::ensure!(
            hex_sha256(&content) == content_sha256,
            "evidence integrity verification failed"
        );
        let base = self.evidence_key_for(tenant_id, profile_id, session_id, evidence_id);
        let object_key = format!("{base}/screenshot.jpeg");
        self.put(&object_key, content.clone()).await?;
        let marker = EvidenceCommitMarker {
            evidence_id,
            evidence_kind,
            content_sha256,
            content_bytes: content.len() as u64,
            captured_at_ms,
        };
        self.put(
            &format!("{base}/COMMITTED"),
            Bytes::from(serde_json::to_vec(&marker)?),
        )
        .await?;
        Ok(object_key)
    }

    pub async fn sign_evidence_download(
        &self,
        request: EvidenceDownloadRequest<'_>,
    ) -> anyhow::Result<(String, u64)> {
        anyhow::ensure!(
            (Duration::from_secs(30)..=Duration::from_secs(120)).contains(&request.expires_in),
            "evidence access duration must be between 30 and 120 seconds"
        );
        let base = self.evidence_key_for(
            request.tenant_id,
            request.profile_id,
            request.session_id,
            request.evidence_id,
        );
        let marker_bytes = self.get(&format!("{base}/COMMITTED")).await?;
        let marker: StoredEvidenceCommitMarker = serde_json::from_slice(&marker_bytes)?;
        anyhow::ensure!(
            marker.evidence_id == request.evidence_id
                && marker.content_sha256 == request.content_sha256
                && marker.content_bytes == request.content_bytes,
            "evidence commit marker does not match the access request"
        );
        let object_key = format!("{base}/screenshot.jpeg");
        let object_path = Path::from(object_key.as_str());
        let metadata = tokio::time::timeout(self.operation_timeout, self.store.head(&object_path))
            .await
            .context("Object Storage operation timed out")?
            .with_context(|| format!("Object Storage HEAD failed for {object_key}"))?;
        anyhow::ensure!(
            metadata.size == request.content_bytes,
            "evidence object size does not match its commit marker"
        );
        let signed_url = tokio::time::timeout(
            self.operation_timeout,
            self.store
                .signed_url(Method::GET, &object_path, request.expires_in),
        )
        .await
        .context("Object Storage signing timed out")?
        .context("Object Storage evidence signing failed")?;
        let expires_at_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)?
            .checked_add(request.expires_in)
            .ok_or_else(|| anyhow::anyhow!("evidence access expiry overflow"))?
            .as_millis() as u64;
        Ok((signed_url.to_string(), expires_at_ms))
    }

    pub async fn sign_profile_export_download(
        &self,
        request: ProfileExportDownloadRequest<'_>,
    ) -> anyhow::Result<SignedProfileExport> {
        anyhow::ensure!(
            (Duration::from_secs(30)..=Duration::from_secs(120)).contains(&request.expires_in),
            "Profile export access duration must be between 30 and 120 seconds"
        );
        let base =
            self.object_key_for(request.tenant_id, request.profile_id, request.checkpoint_id);
        let marker_bytes = self.get(&format!("{base}/COMMITTED")).await?;
        let marker: StoredArchiveCommitMarker = serde_json::from_slice(&marker_bytes)?;
        anyhow::ensure!(
            marker.checkpoint_id == request.checkpoint_id
                && marker.archive_sha256.len() == 64
                && marker
                    .archive_sha256
                    .chars()
                    .all(|character| character.is_ascii_hexdigit())
                && (1..=MAX_PROFILE_EXPORT_BYTES).contains(&marker.archive_bytes),
            "Profile archive commit marker is invalid"
        );
        let object_key = format!("{base}/checkpoint.tar.zst");
        let object_path = Path::from(object_key.as_str());
        let metadata = tokio::time::timeout(self.operation_timeout, self.store.head(&object_path))
            .await
            .context("Object Storage operation timed out")?
            .with_context(|| format!("Object Storage HEAD failed for {object_key}"))?;
        anyhow::ensure!(
            metadata.size == marker.archive_bytes as u64,
            "Profile archive size does not match its commit marker"
        );

        // A signed export is a high-risk data disclosure. Re-read the immutable object as a
        // bounded stream and verify the marker hash immediately before issuing the URL.
        let get_result = tokio::time::timeout(self.operation_timeout, self.store.get(&object_path))
            .await
            .context("Object Storage operation timed out")?
            .with_context(|| format!("Object Storage GET failed for {object_key}"))?;
        let mut stream = get_result.into_stream();
        let mut digest = Sha256::new();
        let mut observed_bytes = 0_u64;
        while let Some(chunk) = tokio::time::timeout(self.operation_timeout, stream.next())
            .await
            .context("Object Storage export verification timed out")?
        {
            let chunk = chunk.context("Object Storage export verification failed")?;
            observed_bytes = observed_bytes
                .checked_add(chunk.len() as u64)
                .ok_or_else(|| anyhow::anyhow!("Profile archive size overflow"))?;
            anyhow::ensure!(
                observed_bytes <= marker.archive_bytes as u64,
                "Profile archive exceeds its commit marker"
            );
            digest.update(&chunk);
        }
        let observed_sha256 = format!("{:x}", digest.finalize());
        anyhow::ensure!(
            observed_bytes == marker.archive_bytes as u64
                && observed_sha256.eq_ignore_ascii_case(&marker.archive_sha256),
            "Profile archive integrity verification failed"
        );

        let signed_url = tokio::time::timeout(
            self.operation_timeout,
            self.store
                .signed_url(Method::GET, &object_path, request.expires_in),
        )
        .await
        .context("Object Storage signing timed out")?
        .context("Object Storage Profile export signing failed")?;
        let expires_at_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)?
            .checked_add(request.expires_in)
            .ok_or_else(|| anyhow::anyhow!("Profile export expiry overflow"))?
            .as_millis() as u64;
        Ok(SignedProfileExport {
            archive_sha256: observed_sha256,
            archive_size_bytes: observed_bytes,
            download_url: signed_url.to_string(),
            expires_at_ms,
        })
    }

    async fn put(&self, key: &str, payload: Bytes) -> anyhow::Result<()> {
        tokio::time::timeout(
            self.operation_timeout,
            self.store
                .put(&Path::from(key), PutPayload::from_bytes(payload)),
        )
        .await
        .context("Object Storage operation timed out")?
        .with_context(|| format!("Object Storage PUT failed for {key}"))?;
        Ok(())
    }

    async fn get(&self, key: &str) -> anyhow::Result<Bytes> {
        tokio::time::timeout(self.operation_timeout, async {
            self.store
                .get(&Path::from(key))
                .await?
                .bytes()
                .await
                .with_context(|| format!("Object Storage GET failed for {key}"))
        })
        .await
        .context("Object Storage operation timed out")?
    }

    fn object_key(&self, manifest: &ProfileCheckpointManifest) -> String {
        self.object_key_for(
            &manifest.tenant_id,
            &manifest.profile_id,
            &manifest.checkpoint_id,
        )
    }

    fn object_key_for(&self, tenant_id: &str, profile_id: &str, checkpoint_id: &str) -> String {
        let suffix =
            format!("tenants/{tenant_id}/profiles/{profile_id}/checkpoints/{checkpoint_id}");
        if self.prefix.is_empty() {
            suffix
        } else {
            format!("{}/{}", self.prefix, suffix)
        }
    }

    fn recording_key_for(
        &self,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
        recording_id: &str,
    ) -> String {
        let suffix = format!(
            "tenants/{tenant_id}/profiles/{profile_id}/sessions/{session_id}/recordings/{recording_id}"
        );
        if self.prefix.is_empty() {
            suffix
        } else {
            format!("{}/{}", self.prefix, suffix)
        }
    }

    fn evidence_key_for(
        &self,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
        evidence_id: &str,
    ) -> String {
        let suffix = format!(
            "tenants/{tenant_id}/profiles/{profile_id}/sessions/{session_id}/evidence/{evidence_id}"
        );
        if self.prefix.is_empty() {
            suffix
        } else {
            format!("{}/{}", self.prefix, suffix)
        }
    }
}

pub struct S3ArchiveConfig {
    pub bucket: String,
    pub region: String,
    pub endpoint: String,
    pub access_key_id: String,
    pub secret_access_key: String,
    pub prefix: String,
    pub connect_timeout: Duration,
    pub operation_timeout: Duration,
    pub allow_http: bool,
}

fn hex_sha256(value: &[u8]) -> String {
    format!("{:x}", Sha256::digest(value))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::time::Instant;

    #[tokio::test]
    #[ignore = "requires TEST_OBJECT_STORAGE_* and an S3-compatible server"]
    async fn archives_checkpoint_or_fails_within_bound() {
        let endpoint = std::env::var("TEST_OBJECT_STORAGE_ENDPOINT").unwrap();
        let expect_failure = std::env::var("TEST_OBJECT_STORAGE_EXPECT_FAILURE")
            .map(|value| value == "true")
            .unwrap_or(false);
        let timeout_millis = std::env::var("TEST_OBJECT_STORAGE_TIMEOUT_MS")
            .unwrap_or_else(|_| "1000".to_owned())
            .parse::<u64>()
            .unwrap();
        let root = std::env::temp_dir().join(format!(
            "browsercloud-object-archive-test-{}",
            uuid::Uuid::new_v4().simple()
        ));
        let local = LocalProfileStore::open(root.clone()).await.unwrap();
        let workspace = local
            .acquire_workspace("tenant-test", "profile-test", "session-test")
            .await
            .unwrap();
        fs::write(workspace.core_dir.join("Cookies"), b"encrypted-test-value").unwrap();
        let manifest = local.checkpoint(&workspace, "runtime-test").await.unwrap();
        let archive = ObjectArchive::s3(S3ArchiveConfig {
            bucket: std::env::var("TEST_OBJECT_STORAGE_BUCKET").unwrap(),
            region: "us-east-1".to_owned(),
            endpoint,
            access_key_id: std::env::var("TEST_OBJECT_STORAGE_ACCESS_KEY_ID").unwrap(),
            secret_access_key: std::env::var("TEST_OBJECT_STORAGE_SECRET_ACCESS_KEY").unwrap(),
            prefix: "acceptance".to_owned(),
            connect_timeout: Duration::from_millis(timeout_millis),
            operation_timeout: Duration::from_millis(timeout_millis),
            allow_http: true,
        })
        .unwrap();

        let started = Instant::now();
        let result = archive.commit_checkpoint(&local, &manifest).await;
        if expect_failure {
            assert!(result.is_err());
            assert!(started.elapsed() < Duration::from_millis(timeout_millis + 1_000));
            assert!(local.pack_checkpoint(&manifest).await.is_ok());
        } else {
            result.unwrap();
            let base = archive.object_key(&manifest);
            archive
                .store
                .get(&Path::from(format!("{base}/checkpoint.tar.zst")))
                .await
                .unwrap();
            archive
                .store
                .get(&Path::from(format!("{base}/manifest.json")))
                .await
                .unwrap();
            archive
                .store
                .get(&Path::from(format!("{base}/COMMITTED")))
                .await
                .unwrap();
            let signed_export = archive
                .sign_profile_export_download(ProfileExportDownloadRequest {
                    tenant_id: "tenant-test",
                    profile_id: "profile-test",
                    checkpoint_id: &manifest.checkpoint_id,
                    expires_in: Duration::from_secs(60),
                })
                .await
                .unwrap();
            let downloaded_export = reqwest::get(signed_export.download_url)
                .await
                .unwrap()
                .error_for_status()
                .unwrap()
                .bytes()
                .await
                .unwrap();
            assert_eq!(
                signed_export.archive_size_bytes,
                downloaded_export.len() as u64
            );
            assert_eq!(signed_export.archive_sha256, hex_sha256(&downloaded_export));
            let recording_content = Bytes::from_static(
                br#"{"capturedAtMs":1,"cdpSessionId":7,"format":"jpeg","data":"/9j/"}"#,
            );
            let recording_hash = hex_sha256(&recording_content);
            let segment_key = archive
                .commit_recording_segment(
                    "tenant-test",
                    "profile-test",
                    "session-test",
                    "rec-test",
                    0,
                    recording_content,
                    &recording_hash,
                    1,
                    1,
                    2,
                )
                .await
                .unwrap();
            archive
                .store
                .get(&Path::from(segment_key.as_str()))
                .await
                .unwrap();
            let recording_base = archive.recording_key_for(
                "tenant-test",
                "profile-test",
                "session-test",
                "rec-test",
            );
            archive
                .store
                .get(&Path::from(format!(
                    "{recording_base}/segments/{:020}.COMMITTED",
                    0
                )))
                .await
                .unwrap();
            let completed_key = archive
                .complete_recording(
                    "tenant-test",
                    "profile-test",
                    "session-test",
                    "rec-test",
                    1,
                    1,
                    1,
                    2,
                )
                .await
                .unwrap();
            archive.store.get(&Path::from(completed_key)).await.unwrap();
            let evidence_content = Bytes::from_static(&[0xff, 0xd8, 0xff, 0xd9]);
            let evidence_hash = hex_sha256(&evidence_content);
            let evidence_key = archive
                .commit_evidence(
                    "tenant-test",
                    "profile-test",
                    "session-test",
                    "evd-test",
                    "AGENT_ACTION_FAILURE",
                    evidence_content,
                    &evidence_hash,
                    3,
                )
                .await
                .unwrap();
            archive
                .store
                .get(&Path::from(evidence_key.as_str()))
                .await
                .unwrap();
            let evidence_base =
                archive.evidence_key_for("tenant-test", "profile-test", "session-test", "evd-test");
            archive
                .store
                .get(&Path::from(format!("{evidence_base}/COMMITTED")))
                .await
                .unwrap();
            let (download_url, expires_at_ms) = archive
                .sign_evidence_download(EvidenceDownloadRequest {
                    tenant_id: "tenant-test",
                    profile_id: "profile-test",
                    session_id: "session-test",
                    evidence_id: "evd-test",
                    content_sha256: &evidence_hash,
                    content_bytes: 4,
                    expires_in: Duration::from_secs(60),
                })
                .await
                .unwrap();
            let downloaded = reqwest::get(download_url)
                .await
                .unwrap()
                .error_for_status()
                .unwrap()
                .bytes()
                .await
                .unwrap();
            assert_eq!(downloaded.as_ref(), &[0xff, 0xd8, 0xff, 0xd9]);
            assert!(
                expires_at_ms
                    > SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .unwrap()
                        .as_millis() as u64
            );
        }
        let _ = fs::remove_dir_all(root);
    }
}
