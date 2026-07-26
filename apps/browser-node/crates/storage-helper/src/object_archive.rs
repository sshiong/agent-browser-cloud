use crate::{LocalProfileStore, ProfileCheckpointManifest};
use anyhow::Context;
use bytes::Bytes;
use object_store::aws::AmazonS3Builder;
use object_store::path::Path;
use object_store::{ClientOptions, ObjectStore, ObjectStoreExt, PutPayload};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::time::Duration;

#[derive(Clone)]
pub struct ObjectArchive {
    store: Arc<dyn ObjectStore>,
    prefix: String,
    operation_timeout: Duration,
}

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

    fn object_key(&self, manifest: &ProfileCheckpointManifest) -> String {
        let suffix = format!(
            "tenants/{}/profiles/{}/checkpoints/{}",
            manifest.tenant_id, manifest.profile_id, manifest.checkpoint_id
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
        }
        let _ = fs::remove_dir_all(root);
    }
}
