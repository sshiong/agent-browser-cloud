use crate::{LocalProfileStore, ProfileCheckpointManifest};
use anyhow::Context;
use aws_sdk_s3::config::{BehaviorVersion, Credentials, Region};
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use bytes::Bytes;
use futures_util::TryStreamExt;
use http::Method;
use object_store::aws::{AmazonS3, AmazonS3Builder};
use object_store::path::Path;
use object_store::signer::Signer;
use object_store::{
    ClientOptions, Error as ObjectStoreError, ObjectStore, ObjectStoreExt, PutMode, PutOptions,
    PutPayload,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs;
use std::io::Read;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::Path as FilePath;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use zeroize::{Zeroize, Zeroizing};

#[derive(Clone)]
pub struct ObjectArchive {
    store: Arc<AmazonS3>,
    version_store: aws_sdk_s3::Client,
    bucket: String,
    prefix: String,
    operation_timeout: Duration,
    profile_crypto: Arc<ProfileArchiveCrypto>,
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

pub struct RecordingPlaybackRequest<'a> {
    pub tenant_id: &'a str,
    pub profile_id: &'a str,
    pub session_id: &'a str,
    pub recording_id: &'a str,
    pub manifest_sha256: &'a str,
    pub manifest_bytes: u64,
    pub segment_count: u64,
    pub frame_count: u64,
    pub redacted_frame_count: u64,
    pub redacted_region_count: u64,
    pub redaction_policy_version: u32,
    pub started_at_ms: u64,
    pub ended_at_ms: u64,
    pub segment_offset: u64,
    pub segment_limit: u32,
    pub expires_in: Duration,
}

pub struct RecordingDeletionRequest<'a> {
    pub deletion_job_id: &'a str,
    pub deletion_epoch: u64,
    pub tenant_id: &'a str,
    pub profile_id: &'a str,
    pub session_id: &'a str,
    pub recording_id: &'a str,
    pub manifest_sha256: &'a str,
    pub manifest_bytes: u64,
    pub segment_count: u64,
}

pub struct RecordingDeletionResult {
    pub deletion_job_id: String,
    pub recording_id: String,
    pub deletion_epoch: u64,
    pub deletion_proof_hash: String,
    pub deleted_object_count: u64,
    pub completed_at_ms: u64,
}

struct RecordingObjectVersion {
    key: String,
    version_id: String,
}

pub struct SignedRecordingPlaybackSegment {
    pub sequence: u64,
    pub content_sha256: String,
    pub content_bytes: u64,
    pub frame_count: u64,
    pub started_at_ms: u64,
    pub ended_at_ms: u64,
    pub download_url: String,
}

pub struct SignedRecordingPlayback {
    pub recording_id: String,
    pub manifest_sha256: String,
    pub frame_count: u64,
    pub redacted_frame_count: u64,
    pub redacted_region_count: u64,
    pub redaction_policy_version: u32,
    pub expires_at_ms: u64,
    pub next_segment_offset: Option<u64>,
    pub segments: Vec<SignedRecordingPlaybackSegment>,
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
const MAX_PROFILE_ARCHIVE_BYTES: usize = 1024 * 1024 * 1024;
const ENCRYPTED_ARCHIVE_OBJECT: &str = "checkpoint.tar.zst.enc";
const LEGACY_ARCHIVE_OBJECT: &str = "checkpoint.tar.zst";
const PROFILE_ARCHIVE_MAGIC: &[u8; 8] = b"BCPAE1\0\0";
const MAX_ENVELOPE_HEADER_BYTES: usize = 16 * 1024;
const AES_GCM_NONCE_BYTES: usize = 12;
const AES_GCM_TAG_BYTES: usize = 16;

pub struct ProfileArchiveCrypto {
    active_key_id: String,
    keys: HashMap<String, [u8; 32]>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProfileArchiveKeyringFile {
    active_key_id: String,
    keys: HashMap<String, String>,
}

impl Drop for ProfileArchiveKeyringFile {
    fn drop(&mut self) {
        for encoded_key in self.keys.values_mut() {
            encoded_key.zeroize();
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProfileArchiveEnvelopeHeader {
    version: u32,
    algorithm: String,
    key_id: String,
    wrapped_key_nonce: String,
    wrapped_data_key: String,
    content_nonce: String,
    tenant_id: String,
    profile_id: String,
    checkpoint_id: String,
    plaintext_sha256: String,
    plaintext_bytes: usize,
}

struct DecryptedProfileArchive {
    plaintext: Zeroizing<Vec<u8>>,
    key_id: String,
    tenant_id: String,
    profile_id: String,
    checkpoint_id: String,
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
    archive_object: &'static str,
    archive_format: &'static str,
    encryption_key_id: &'a str,
    plaintext_archive_sha256: String,
    plaintext_archive_bytes: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredArchiveCommitMarker {
    checkpoint_id: String,
    #[serde(default)]
    checkpoint_epoch: u64,
    #[serde(default)]
    profile_write_epoch: u64,
    #[serde(default)]
    content_hash: String,
    archive_sha256: String,
    archive_bytes: usize,
    #[serde(default = "legacy_archive_object")]
    archive_object: String,
    #[serde(default = "legacy_archive_format")]
    archive_format: String,
    #[serde(default)]
    encryption_key_id: Option<String>,
    #[serde(default)]
    plaintext_archive_sha256: Option<String>,
    #[serde(default)]
    plaintext_archive_bytes: Option<usize>,
}

fn legacy_archive_object() -> String {
    LEGACY_ARCHIVE_OBJECT.to_owned()
}

fn legacy_archive_format() -> String {
    "TAR_ZSTD".to_owned()
}

impl Drop for ProfileArchiveCrypto {
    fn drop(&mut self) {
        for key in self.keys.values_mut() {
            key.zeroize();
        }
    }
}

impl ProfileArchiveCrypto {
    pub fn from_keyring_file(path: &FilePath) -> anyhow::Result<Self> {
        let metadata = fs::symlink_metadata(path)
            .with_context(|| format!("inspect Profile archive keyring {}", path.display()))?;
        anyhow::ensure!(
            metadata.file_type().is_file() && !metadata.file_type().is_symlink(),
            "Profile archive keyring must be a regular file"
        );
        anyhow::ensure!(
            metadata.permissions().mode() & 0o022 == 0
                && metadata.permissions().mode() & 0o004 == 0,
            "Profile archive keyring must not be writable by group/other or readable by other"
        );
        anyhow::ensure!(
            metadata.size() <= 64 * 1024,
            "Profile archive keyring exceeds 64 KiB"
        );
        let mut file = fs::OpenOptions::new()
            .read(true)
            .custom_flags(nix::libc::O_NOFOLLOW)
            .open(path)
            .with_context(|| format!("securely open Profile archive keyring {}", path.display()))?;
        let opened_metadata = file.metadata()?;
        anyhow::ensure!(
            opened_metadata.is_file()
                && opened_metadata.dev() == metadata.dev()
                && opened_metadata.ino() == metadata.ino()
                && opened_metadata.len() == metadata.len(),
            "Profile archive keyring changed during secure open"
        );
        let mut keyring_bytes = Vec::with_capacity(metadata.len() as usize);
        file.read_to_end(&mut keyring_bytes)?;
        anyhow::ensure!(
            keyring_bytes.len() as u64 == metadata.len(),
            "Profile archive keyring changed during read"
        );
        let encoded_result =
            serde_json::from_slice(&keyring_bytes).context("parse Profile archive keyring");
        keyring_bytes.zeroize();
        let mut encoded: ProfileArchiveKeyringFile = encoded_result?;
        validate_key_id(&encoded.active_key_id)?;
        anyhow::ensure!(
            !encoded.keys.is_empty() && encoded.keys.len() <= 16,
            "Profile archive keyring must contain between 1 and 16 keys"
        );
        let mut keys = HashMap::with_capacity(encoded.keys.len());
        for (key_id, encoded_key) in encoded.keys.drain() {
            validate_key_id(&key_id)?;
            let encoded_key = Zeroizing::new(encoded_key);
            let mut decoded = BASE64
                .decode(encoded_key.as_bytes())
                .with_context(|| format!("Profile archive key {key_id} is not valid base64"))?;
            anyhow::ensure!(
                decoded.len() == 32,
                "Profile archive key {key_id} must decode to exactly 32 bytes"
            );
            let mut key = [0_u8; 32];
            key.copy_from_slice(&decoded);
            decoded.zeroize();
            anyhow::ensure!(
                keys.insert(key_id, key).is_none(),
                "Profile archive keyring contains a duplicate key id"
            );
        }
        anyhow::ensure!(
            keys.contains_key(&encoded.active_key_id),
            "Profile archive active key id is absent from the keyring"
        );
        Ok(Self {
            active_key_id: encoded.active_key_id.clone(),
            keys,
        })
    }

    #[cfg(test)]
    fn for_test(active_key_id: &str, keys: &[(&str, [u8; 32])]) -> Self {
        Self {
            active_key_id: active_key_id.to_owned(),
            keys: keys
                .iter()
                .map(|(key_id, key)| ((*key_id).to_owned(), *key))
                .collect(),
        }
    }

    fn active_key_id(&self) -> &str {
        &self.active_key_id
    }

    fn encrypt(
        &self,
        tenant_id: &str,
        profile_id: &str,
        checkpoint_id: &str,
        plaintext: &[u8],
    ) -> anyhow::Result<Vec<u8>> {
        anyhow::ensure!(
            !plaintext.is_empty() && plaintext.len() <= MAX_PROFILE_ARCHIVE_BYTES,
            "Profile archive plaintext size is invalid"
        );
        let plaintext_sha256 = hex_sha256(plaintext);
        let mut data_key = Zeroizing::new([0_u8; 32]);
        let mut wrap_nonce = [0_u8; AES_GCM_NONCE_BYTES];
        let mut content_nonce = [0_u8; AES_GCM_NONCE_BYTES];
        let random = ring::rand::SystemRandom::new();
        use ring::rand::SecureRandom;
        random
            .fill(data_key.as_mut())
            .map_err(|_| anyhow::anyhow!("generate Profile archive data key"))?;
        random
            .fill(&mut wrap_nonce)
            .map_err(|_| anyhow::anyhow!("generate Profile archive wrap nonce"))?;
        random
            .fill(&mut content_nonce)
            .map_err(|_| anyhow::anyhow!("generate Profile archive content nonce"))?;

        let key_encryption_key = self
            .keys
            .get(&self.active_key_id)
            .ok_or_else(|| anyhow::anyhow!("Profile archive active key is unavailable"))?;
        let wrapping_key = ring::aead::LessSafeKey::new(
            ring::aead::UnboundKey::new(&ring::aead::AES_256_GCM, key_encryption_key)
                .map_err(|_| anyhow::anyhow!("initialize Profile archive wrapping key"))?,
        );
        let mut wrapped_data_key = Zeroizing::new(data_key.to_vec());
        wrapping_key
            .seal_in_place_append_tag(
                ring::aead::Nonce::assume_unique_for_key(wrap_nonce),
                ring::aead::Aad::from(wrap_aad(&self.active_key_id).as_slice()),
                &mut *wrapped_data_key,
            )
            .map_err(|_| anyhow::anyhow!("wrap Profile archive data key"))?;

        let header = ProfileArchiveEnvelopeHeader {
            version: 1,
            algorithm: "AES_256_GCM".to_owned(),
            key_id: self.active_key_id.clone(),
            wrapped_key_nonce: BASE64.encode(wrap_nonce),
            wrapped_data_key: BASE64.encode(wrapped_data_key.as_slice()),
            content_nonce: BASE64.encode(content_nonce),
            tenant_id: tenant_id.to_owned(),
            profile_id: profile_id.to_owned(),
            checkpoint_id: checkpoint_id.to_owned(),
            plaintext_sha256,
            plaintext_bytes: plaintext.len(),
        };
        let header_bytes = serde_json::to_vec(&header)?;
        anyhow::ensure!(
            header_bytes.len() <= MAX_ENVELOPE_HEADER_BYTES,
            "Profile archive envelope header is too large"
        );
        let content_key = ring::aead::LessSafeKey::new(
            ring::aead::UnboundKey::new(&ring::aead::AES_256_GCM, data_key.as_ref())
                .map_err(|_| anyhow::anyhow!("initialize Profile archive content key"))?,
        );
        let mut ciphertext = plaintext.to_vec();
        content_key
            .seal_in_place_append_tag(
                ring::aead::Nonce::assume_unique_for_key(content_nonce),
                ring::aead::Aad::from(content_aad(&header).as_slice()),
                &mut ciphertext,
            )
            .map_err(|_| anyhow::anyhow!("encrypt Profile archive"))?;
        let mut envelope = Vec::with_capacity(
            PROFILE_ARCHIVE_MAGIC.len() + 4 + header_bytes.len() + ciphertext.len(),
        );
        envelope.extend_from_slice(PROFILE_ARCHIVE_MAGIC);
        envelope.extend_from_slice(&(header_bytes.len() as u32).to_be_bytes());
        envelope.extend_from_slice(&header_bytes);
        envelope.extend_from_slice(&ciphertext);
        ciphertext.zeroize();
        anyhow::ensure!(
            envelope.len() <= MAX_PROFILE_ARCHIVE_BYTES + MAX_ENVELOPE_HEADER_BYTES + 64,
            "encrypted Profile archive exceeds the archive size limit"
        );
        Ok(envelope)
    }

    fn decrypt(&self, envelope: &[u8]) -> anyhow::Result<DecryptedProfileArchive> {
        anyhow::ensure!(
            envelope.len() > PROFILE_ARCHIVE_MAGIC.len() + 4 + AES_GCM_TAG_BYTES
                && envelope.starts_with(PROFILE_ARCHIVE_MAGIC),
            "Profile archive is not an encrypted envelope"
        );
        let header_length_offset = PROFILE_ARCHIVE_MAGIC.len();
        let header_length = u32::from_be_bytes(
            envelope[header_length_offset..header_length_offset + 4]
                .try_into()
                .expect("fixed header length slice"),
        ) as usize;
        anyhow::ensure!(
            (1..=MAX_ENVELOPE_HEADER_BYTES).contains(&header_length),
            "Profile archive envelope header length is invalid"
        );
        let ciphertext_offset = header_length_offset + 4 + header_length;
        anyhow::ensure!(
            ciphertext_offset + AES_GCM_TAG_BYTES <= envelope.len()
                && envelope.len() <= MAX_PROFILE_ARCHIVE_BYTES + MAX_ENVELOPE_HEADER_BYTES + 64,
            "Profile archive envelope size is invalid"
        );
        let header: ProfileArchiveEnvelopeHeader =
            serde_json::from_slice(&envelope[header_length_offset + 4..ciphertext_offset])
                .context("parse Profile archive envelope header")?;
        anyhow::ensure!(
            header.version == 1 && header.algorithm == "AES_256_GCM",
            "Profile archive envelope algorithm is unsupported"
        );
        validate_key_id(&header.key_id)?;
        anyhow::ensure!(
            !header.tenant_id.is_empty()
                && !header.profile_id.is_empty()
                && !header.checkpoint_id.is_empty()
                && header.plaintext_sha256.len() == 64
                && header
                    .plaintext_sha256
                    .chars()
                    .all(|character| character.is_ascii_hexdigit())
                && (1..=MAX_PROFILE_ARCHIVE_BYTES).contains(&header.plaintext_bytes),
            "Profile archive envelope metadata is invalid"
        );
        let wrap_nonce = decode_nonce(&header.wrapped_key_nonce, "wrapped key")?;
        let content_nonce = decode_nonce(&header.content_nonce, "content")?;
        let mut wrapped_data_key = Zeroizing::new(
            BASE64
                .decode(header.wrapped_data_key.as_bytes())
                .context("Profile archive wrapped data key is not valid base64")?,
        );
        anyhow::ensure!(
            wrapped_data_key.len() == 32 + AES_GCM_TAG_BYTES,
            "Profile archive wrapped data key has an invalid size"
        );
        let key_encryption_key = self
            .keys
            .get(&header.key_id)
            .ok_or_else(|| anyhow::anyhow!("Profile archive encryption key is unavailable"))?;
        let wrapping_key = ring::aead::LessSafeKey::new(
            ring::aead::UnboundKey::new(&ring::aead::AES_256_GCM, key_encryption_key)
                .map_err(|_| anyhow::anyhow!("initialize Profile archive wrapping key"))?,
        );
        let opened_data_key_length = wrapping_key
            .open_in_place(
                ring::aead::Nonce::assume_unique_for_key(wrap_nonce),
                ring::aead::Aad::from(wrap_aad(&header.key_id).as_slice()),
                wrapped_data_key.as_mut_slice(),
            )
            .map_err(|_| anyhow::anyhow!("Profile archive data key authentication failed"))?
            .len();
        anyhow::ensure!(
            opened_data_key_length == 32,
            "Profile archive data key has an invalid size"
        );
        let mut data_key = Zeroizing::new([0_u8; 32]);
        data_key.copy_from_slice(&wrapped_data_key[..opened_data_key_length]);

        let content_key = ring::aead::LessSafeKey::new(
            ring::aead::UnboundKey::new(&ring::aead::AES_256_GCM, data_key.as_ref())
                .map_err(|_| anyhow::anyhow!("initialize Profile archive content key"))?,
        );
        let mut plaintext = envelope[ciphertext_offset..].to_vec();
        let plaintext_length = content_key
            .open_in_place(
                ring::aead::Nonce::assume_unique_for_key(content_nonce),
                ring::aead::Aad::from(content_aad(&header).as_slice()),
                &mut plaintext,
            )
            .map_err(|_| anyhow::anyhow!("Profile archive authentication failed"))?
            .len();
        plaintext.truncate(plaintext_length);
        anyhow::ensure!(
            plaintext.len() == header.plaintext_bytes
                && hex_sha256(&plaintext).eq_ignore_ascii_case(&header.plaintext_sha256),
            "Profile archive plaintext integrity verification failed"
        );
        Ok(DecryptedProfileArchive {
            plaintext: Zeroizing::new(plaintext),
            key_id: header.key_id,
            tenant_id: header.tenant_id,
            profile_id: header.profile_id,
            checkpoint_id: header.checkpoint_id,
        })
    }
}

fn validate_key_id(key_id: &str) -> anyhow::Result<()> {
    anyhow::ensure!(
        (1..=64).contains(&key_id.len())
            && key_id.chars().all(
                |character| character.is_ascii_alphanumeric() || matches!(character, '_' | '-')
            ),
        "Profile archive key id is invalid"
    );
    Ok(())
}

fn decode_nonce(value: &str, label: &str) -> anyhow::Result<[u8; AES_GCM_NONCE_BYTES]> {
    let decoded = BASE64
        .decode(value.as_bytes())
        .with_context(|| format!("Profile archive {label} nonce is not valid base64"))?;
    anyhow::ensure!(
        decoded.len() == AES_GCM_NONCE_BYTES,
        "Profile archive {label} nonce has an invalid size"
    );
    Ok(decoded.try_into().expect("validated nonce length"))
}

fn wrap_aad(key_id: &str) -> Vec<u8> {
    format!("browsercloud/profile-checkpoint/dek/v1\0{key_id}").into_bytes()
}

fn content_aad(header: &ProfileArchiveEnvelopeHeader) -> Vec<u8> {
    format!(
        "browsercloud/profile-checkpoint/content/v1\0{}\0{}\0{}\0{}\0{}",
        header.tenant_id,
        header.profile_id,
        header.checkpoint_id,
        header.plaintext_sha256,
        header.plaintext_bytes
    )
    .into_bytes()
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RecordingSegmentMarker<'a> {
    recording_id: &'a str,
    segment_sequence: u64,
    content_sha256: &'a str,
    content_bytes: u64,
    frame_count: u64,
    redacted_frame_count: u64,
    redacted_region_count: u64,
    redaction_policy_version: u32,
    started_at_ms: u64,
    ended_at_ms: u64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RecordingCommitMarker<'a> {
    recording_id: &'a str,
    segment_count: u64,
    frame_count: u64,
    redacted_frame_count: u64,
    redacted_region_count: u64,
    redaction_policy_version: u32,
    started_at_ms: u64,
    ended_at_ms: u64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredRecordingSegmentMarker {
    recording_id: String,
    segment_sequence: u64,
    content_sha256: String,
    content_bytes: u64,
    frame_count: u64,
    redacted_frame_count: u64,
    redacted_region_count: u64,
    redaction_policy_version: u32,
    started_at_ms: u64,
    ended_at_ms: u64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredRecordingCommitMarker {
    recording_id: String,
    segment_count: u64,
    frame_count: u64,
    redacted_frame_count: u64,
    redacted_region_count: u64,
    redaction_policy_version: u32,
    started_at_ms: u64,
    ended_at_ms: u64,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RecordingDeletionMarker {
    version: u32,
    state: String,
    deletion_job_id: String,
    deletion_epoch: u64,
    tenant_id: String,
    profile_id: String,
    session_id: String,
    recording_id: String,
    manifest_sha256: String,
    manifest_bytes: u64,
    segment_count: u64,
    deleted_object_count: u64,
    completed_at_ms: Option<u64>,
}

pub struct RecordingCommitResult {
    pub object_key: String,
    pub manifest_sha256: String,
    pub manifest_bytes: u64,
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
            .with_bucket_name(config.bucket.clone())
            .with_region(config.region.clone())
            .with_endpoint(config.endpoint.clone())
            .with_access_key_id(config.access_key_id.clone())
            .with_secret_access_key(config.secret_access_key.clone())
            .with_allow_http(config.allow_http)
            .with_client_options(client_options)
            .build()
            .context("build S3-compatible Object Storage client")?;
        let version_store_config = aws_sdk_s3::Config::builder()
            .behavior_version(BehaviorVersion::latest())
            .region(Region::new(config.region))
            .endpoint_url(config.endpoint)
            .credentials_provider(Credentials::new(
                config.access_key_id,
                config.secret_access_key,
                None,
                None,
                "browsercloud-object-archive",
            ))
            .force_path_style(true)
            .build();
        Ok(Self {
            store: Arc::new(store),
            version_store: aws_sdk_s3::Client::from_conf(version_store_config),
            bucket: config.bucket,
            prefix: config.prefix.trim_matches('/').to_owned(),
            operation_timeout: config.operation_timeout,
            profile_crypto: Arc::new(config.profile_crypto),
        })
    }

    pub async fn commit_checkpoint(
        &self,
        local: &LocalProfileStore,
        manifest: &ProfileCheckpointManifest,
    ) -> anyhow::Result<()> {
        let archive = local.pack_checkpoint(manifest).await?;
        self.commit_encrypted_checkpoint(manifest, &archive).await
    }

    pub fn is_encrypted_profile_archive(value: &[u8]) -> bool {
        value.starts_with(PROFILE_ARCHIVE_MAGIC)
    }

    pub fn decrypt_profile_import(&self, value: &[u8]) -> anyhow::Result<Vec<u8>> {
        self.profile_crypto
            .decrypt(value)
            .map(|archive| archive.plaintext.to_vec())
    }

    async fn commit_encrypted_checkpoint(
        &self,
        manifest: &ProfileCheckpointManifest,
        plaintext_archive: &[u8],
    ) -> anyhow::Result<()> {
        let plaintext_hash = hex_sha256(plaintext_archive);
        let encrypted_archive = self.profile_crypto.encrypt(
            &manifest.tenant_id,
            &manifest.profile_id,
            &manifest.checkpoint_id,
            plaintext_archive,
        )?;
        let archive_hash = hex_sha256(&encrypted_archive);
        let base = self.object_key(manifest);
        self.put(
            &format!("{base}/{ENCRYPTED_ARCHIVE_OBJECT}"),
            Bytes::from(encrypted_archive.clone()),
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
            archive_bytes: encrypted_archive.len(),
            archive_object: ENCRYPTED_ARCHIVE_OBJECT,
            archive_format: "BROWSERCLOUD_PROFILE_AEAD_V1",
            encryption_key_id: self.profile_crypto.active_key_id(),
            plaintext_archive_sha256: plaintext_hash,
            plaintext_archive_bytes: plaintext_archive.len(),
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
        let archive = self
            .get(&format!("{base}/{}", marker.archive_object))
            .await?;
        anyhow::ensure!(
            archive.len() == marker.archive_bytes && hex_sha256(&archive) == marker.archive_sha256,
            "checkpoint archive integrity verification failed"
        );
        let is_legacy = marker.archive_format == "TAR_ZSTD";
        let plaintext_archive = if is_legacy {
            archive.to_vec()
        } else {
            anyhow::ensure!(
                marker.archive_format == "BROWSERCLOUD_PROFILE_AEAD_V1"
                    && marker.archive_object == ENCRYPTED_ARCHIVE_OBJECT,
                "checkpoint archive format is unsupported"
            );
            let decrypted = self.profile_crypto.decrypt(&archive)?;
            let plaintext_sha256 = hex_sha256(&decrypted.plaintext);
            anyhow::ensure!(
                marker.encryption_key_id.as_deref() == Some(decrypted.key_id.as_str())
                    && marker.plaintext_archive_sha256.as_deref()
                        == Some(plaintext_sha256.as_str())
                    && marker.plaintext_archive_bytes == Some(decrypted.plaintext.len()),
                "checkpoint encryption metadata does not match its commit marker"
            );
            decrypted.plaintext.to_vec()
        };
        let restored = local
            .install_checkpoint_archive(
                tenant_id,
                profile_id,
                checkpoint_id,
                plaintext_archive.clone(),
            )
            .await?;
        if is_legacy {
            self.commit_encrypted_checkpoint(&restored, &plaintext_archive)
                .await?;
            self.delete(&format!("{base}/{LEGACY_ARCHIVE_OBJECT}"))
                .await?;
        }
        Ok(restored)
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
        redacted_frame_count: u64,
        redacted_region_count: u64,
        redaction_policy_version: u32,
        started_at_ms: u64,
        ended_at_ms: u64,
    ) -> anyhow::Result<String> {
        anyhow::ensure!(
            hex_sha256(&content) == content_sha256,
            "recording segment integrity verification failed"
        );
        let base = self.recording_key_for(tenant_id, profile_id, session_id, recording_id);
        let object_key = format!("{base}/segments/{segment_sequence:020}.ndjson");
        self.put_immutable(&object_key, content.clone()).await?;
        let marker = RecordingSegmentMarker {
            recording_id,
            segment_sequence,
            content_sha256,
            content_bytes: content.len() as u64,
            frame_count,
            redacted_frame_count,
            redacted_region_count,
            redaction_policy_version,
            started_at_ms,
            ended_at_ms,
        };
        self.put_immutable(
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
        redacted_frame_count: u64,
        redacted_region_count: u64,
        redaction_policy_version: u32,
        started_at_ms: u64,
        ended_at_ms: u64,
    ) -> anyhow::Result<RecordingCommitResult> {
        let base = self.recording_key_for(tenant_id, profile_id, session_id, recording_id);
        let marker = RecordingCommitMarker {
            recording_id,
            segment_count,
            frame_count,
            redacted_frame_count,
            redacted_region_count,
            redaction_policy_version,
            started_at_ms,
            ended_at_ms,
        };
        let object_key = format!("{base}/COMMITTED");
        let manifest = serde_json::to_vec(&marker)?;
        let manifest_sha256 = hex_sha256(&manifest);
        let manifest_bytes = manifest.len() as u64;
        self.put_immutable(&object_key, Bytes::from(manifest))
            .await?;
        Ok(RecordingCommitResult {
            object_key,
            manifest_sha256,
            manifest_bytes,
        })
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
        self.put_immutable(&object_key, content.clone()).await?;
        let marker = EvidenceCommitMarker {
            evidence_id,
            evidence_kind,
            content_sha256,
            content_bytes: content.len() as u64,
            captured_at_ms,
        };
        self.put_immutable(
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

    pub async fn sign_recording_playback(
        &self,
        request: RecordingPlaybackRequest<'_>,
    ) -> anyhow::Result<SignedRecordingPlayback> {
        anyhow::ensure!(
            (Duration::from_secs(30)..=Duration::from_secs(120)).contains(&request.expires_in)
                && (1..=24).contains(&request.segment_limit)
                && request.segment_offset <= request.segment_count
                && (1..=2).contains(&request.redaction_policy_version)
                && request.redacted_frame_count <= request.frame_count
                && (request.redacted_frame_count == 0 || request.redacted_region_count > 0)
                && request.ended_at_ms >= request.started_at_ms,
            "recording playback request is invalid"
        );
        let base = self.recording_key_for(
            request.tenant_id,
            request.profile_id,
            request.session_id,
            request.recording_id,
        );
        let manifest_bytes = self.get(&format!("{base}/COMMITTED")).await?;
        anyhow::ensure!(
            manifest_bytes.len() as u64 == request.manifest_bytes
                && hex_sha256(&manifest_bytes).eq_ignore_ascii_case(request.manifest_sha256),
            "recording aggregate manifest integrity does not match the access request"
        );
        let manifest: StoredRecordingCommitMarker = serde_json::from_slice(&manifest_bytes)?;
        anyhow::ensure!(
            manifest.recording_id == request.recording_id
                && manifest.segment_count == request.segment_count
                && manifest.frame_count == request.frame_count
                && manifest.redacted_frame_count == request.redacted_frame_count
                && manifest.redacted_region_count == request.redacted_region_count
                && manifest.redaction_policy_version == request.redaction_policy_version
                && manifest.started_at_ms == request.started_at_ms
                && manifest.ended_at_ms == request.ended_at_ms,
            "recording aggregate manifest does not match the access request"
        );
        let page_end = request
            .segment_offset
            .saturating_add(u64::from(request.segment_limit))
            .min(request.segment_count);
        let mut segments = Vec::with_capacity((page_end - request.segment_offset) as usize);
        for sequence in request.segment_offset..page_end {
            let marker_key = format!("{base}/segments/{sequence:020}.COMMITTED");
            let marker_bytes = self.get(&marker_key).await?;
            let marker: StoredRecordingSegmentMarker = serde_json::from_slice(&marker_bytes)?;
            anyhow::ensure!(
                marker.recording_id == request.recording_id
                    && marker.segment_sequence == sequence
                    && marker.content_sha256.len() == 64
                    && marker
                        .content_sha256
                        .chars()
                        .all(|character| character.is_ascii_hexdigit())
                    && marker.content_bytes > 0
                    && marker.redacted_frame_count <= marker.frame_count
                    && (marker.redacted_frame_count == 0 || marker.redacted_region_count > 0)
                    && marker.redaction_policy_version == request.redaction_policy_version
                    && marker.ended_at_ms >= marker.started_at_ms
                    && marker.started_at_ms >= request.started_at_ms
                    && marker.ended_at_ms <= request.ended_at_ms,
                "recording segment marker is invalid"
            );
            let object_key = format!("{base}/segments/{sequence:020}.ndjson");
            let object_path = Path::from(object_key.as_str());
            let metadata =
                tokio::time::timeout(self.operation_timeout, self.store.head(&object_path))
                    .await
                    .context("Object Storage operation timed out")?
                    .with_context(|| format!("Object Storage HEAD failed for {object_key}"))?;
            anyhow::ensure!(
                metadata.size == marker.content_bytes,
                "recording segment size does not match its commit marker"
            );
            let signed_url = tokio::time::timeout(
                self.operation_timeout,
                self.store
                    .signed_url(Method::GET, &object_path, request.expires_in),
            )
            .await
            .context("Object Storage signing timed out")?
            .context("Object Storage recording segment signing failed")?;
            anyhow::ensure!(
                signed_url.as_str().len() <= 2048,
                "recording segment signed URL exceeds the bounded playback response"
            );
            segments.push(SignedRecordingPlaybackSegment {
                sequence,
                content_sha256: marker.content_sha256,
                content_bytes: marker.content_bytes,
                frame_count: marker.frame_count,
                started_at_ms: marker.started_at_ms,
                ended_at_ms: marker.ended_at_ms,
                download_url: signed_url.to_string(),
            });
        }
        let expires_at_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)?
            .checked_add(request.expires_in)
            .ok_or_else(|| anyhow::anyhow!("recording playback expiry overflow"))?
            .as_millis() as u64;
        Ok(SignedRecordingPlayback {
            recording_id: request.recording_id.to_owned(),
            manifest_sha256: request.manifest_sha256.to_ascii_lowercase(),
            frame_count: request.frame_count,
            redacted_frame_count: request.redacted_frame_count,
            redacted_region_count: request.redacted_region_count,
            redaction_policy_version: request.redaction_policy_version,
            expires_at_ms,
            next_segment_offset: (page_end < request.segment_count).then_some(page_end),
            segments,
        })
    }

    pub async fn delete_recording(
        &self,
        request: RecordingDeletionRequest<'_>,
    ) -> anyhow::Result<RecordingDeletionResult> {
        anyhow::ensure!(
            request.deletion_epoch > 0
                && request.manifest_sha256.len() == 64
                && request
                    .manifest_sha256
                    .chars()
                    .all(|character| character.is_ascii_hexdigit())
                && request.manifest_bytes > 0
                && request.segment_count <= 100_000,
            "recording deletion request is invalid"
        );
        let base = self.recording_key_for(
            request.tenant_id,
            request.profile_id,
            request.session_id,
            request.recording_id,
        );
        let deletion_base = self.recording_deletion_key_for(
            request.tenant_id,
            request.profile_id,
            request.session_id,
            request.recording_id,
        );
        let prepared_key = format!("{deletion_base}/PREPARED");
        let committed_key = format!("{deletion_base}/COMMITTED");
        if let Some(committed_bytes) = self.get_optional(&committed_key).await? {
            return validate_recording_deletion_marker(&committed_bytes, &request, "COMMITTED");
        }

        let expected_object_count = request
            .segment_count
            .checked_mul(2)
            .and_then(|count| count.checked_add(1))
            .ok_or_else(|| anyhow::anyhow!("recording deletion object count overflow"))?;
        let prepared = self.get_optional(&prepared_key).await?;
        if let Some(prepared_bytes) = prepared.as_ref() {
            validate_recording_deletion_marker(prepared_bytes, &request, "PREPARED")?;
        } else {
            let manifest_bytes = self.get(&format!("{base}/COMMITTED")).await?;
            anyhow::ensure!(
                manifest_bytes.len() as u64 == request.manifest_bytes
                    && hex_sha256(&manifest_bytes).eq_ignore_ascii_case(request.manifest_sha256),
                "recording aggregate manifest integrity does not match the deletion request"
            );
            let manifest: StoredRecordingCommitMarker = serde_json::from_slice(&manifest_bytes)?;
            anyhow::ensure!(
                manifest.recording_id == request.recording_id
                    && manifest.segment_count == request.segment_count,
                "recording aggregate manifest does not match the deletion request"
            );
            let initial_keys = self.recording_object_keys(&base).await?;
            anyhow::ensure!(
                initial_keys.len() as u64 == expected_object_count,
                "recording object set is incomplete before deletion preparation"
            );
            self.validate_recording_object_keys(
                &base,
                request.recording_id,
                request.segment_count,
                &initial_keys,
            )
            .await?;
            let marker = recording_deletion_marker(&request, "PREPARED", None);
            self.put_immutable(&prepared_key, Bytes::from(serde_json::to_vec(&marker)?))
                .await?;
        }

        let remaining_keys = self.recording_object_keys(&base).await?;
        self.validate_recording_object_key_subset(&base, request.segment_count, &remaining_keys)?;
        let maximum_version_count = expected_object_count
            .checked_mul(2)
            .ok_or_else(|| anyhow::anyhow!("recording version count bound overflow"))?;
        let remaining_versions = self
            .recording_object_versions(&base, maximum_version_count)
            .await?;
        let version_keys = remaining_versions
            .iter()
            .map(|version| version.key.clone())
            .collect::<Vec<_>>();
        self.validate_recording_object_key_subset(&base, request.segment_count, &version_keys)?;
        if remaining_versions.is_empty() {
            let manifest_key = format!("{base}/COMMITTED");
            for key in remaining_keys.iter().filter(|key| *key != &manifest_key) {
                self.delete(key).await?;
            }
            if remaining_keys.iter().any(|key| key == &manifest_key) {
                self.delete(&manifest_key).await?;
            }
        } else {
            for version in &remaining_versions {
                self.delete_version(version).await?;
            }
        }
        anyhow::ensure!(
            self.recording_object_keys(&base).await?.is_empty()
                && self
                    .recording_object_versions(&base, maximum_version_count)
                    .await?
                    .is_empty(),
            "recording object prefix still contains current objects or retained versions after deletion"
        );
        let completed_at_ms = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as u64;
        let committed = recording_deletion_marker(&request, "COMMITTED", Some(completed_at_ms));
        let committed_bytes = serde_json::to_vec(&committed)?;
        let deletion_proof_hash = hex_sha256(&committed_bytes);
        self.put_immutable(&committed_key, Bytes::from(committed_bytes))
            .await?;
        Ok(RecordingDeletionResult {
            deletion_job_id: request.deletion_job_id.to_owned(),
            recording_id: request.recording_id.to_owned(),
            deletion_epoch: request.deletion_epoch,
            deletion_proof_hash,
            deleted_object_count: expected_object_count,
            completed_at_ms,
        })
    }

    async fn recording_object_keys(&self, base: &str) -> anyhow::Result<Vec<String>> {
        let prefix = Path::from(format!("{base}/"));
        tokio::time::timeout(
            self.operation_timeout,
            self.store
                .list(Some(&prefix))
                .map_ok(|metadata| metadata.location.to_string())
                .try_collect::<Vec<_>>(),
        )
        .await
        .context("Object Storage listing timed out")?
        .context("Object Storage recording listing failed")
    }

    async fn recording_object_versions(
        &self,
        base: &str,
        maximum_version_count: u64,
    ) -> anyhow::Result<Vec<RecordingObjectVersion>> {
        let prefix = format!("{base}/");
        let mut versions = Vec::new();
        let mut key_marker = None;
        let mut version_id_marker = None;
        loop {
            let response = tokio::time::timeout(
                self.operation_timeout,
                self.version_store
                    .list_object_versions()
                    .bucket(&self.bucket)
                    .prefix(&prefix)
                    .set_key_marker(key_marker)
                    .set_version_id_marker(version_id_marker)
                    .send(),
            )
            .await
            .context("Object Storage version listing timed out")?
            .context("Object Storage recording version listing failed")?;

            for version in response.versions() {
                let key = version
                    .key()
                    .ok_or_else(|| anyhow::anyhow!("Object Storage version omitted its key"))?;
                let version_id = version.version_id().ok_or_else(|| {
                    anyhow::anyhow!("Object Storage version omitted its version id")
                })?;
                versions.push(RecordingObjectVersion {
                    key: key.to_owned(),
                    version_id: version_id.to_owned(),
                });
            }
            for marker in response.delete_markers() {
                let key = marker.key().ok_or_else(|| {
                    anyhow::anyhow!("Object Storage delete marker omitted its key")
                })?;
                let version_id = marker.version_id().ok_or_else(|| {
                    anyhow::anyhow!("Object Storage delete marker omitted its version id")
                })?;
                versions.push(RecordingObjectVersion {
                    key: key.to_owned(),
                    version_id: version_id.to_owned(),
                });
            }
            anyhow::ensure!(
                versions.len() as u64 <= maximum_version_count,
                "recording object prefix contains too many object versions"
            );
            if !response.is_truncated().unwrap_or(false) {
                break;
            }
            key_marker = response.next_key_marker().map(str::to_owned);
            version_id_marker = response.next_version_id_marker().map(str::to_owned);
            anyhow::ensure!(
                key_marker.is_some(),
                "truncated Object Storage version listing omitted its next key marker"
            );
        }
        Ok(versions)
    }

    async fn delete_version(&self, version: &RecordingObjectVersion) -> anyhow::Result<()> {
        tokio::time::timeout(
            self.operation_timeout,
            self.version_store
                .delete_object()
                .bucket(&self.bucket)
                .key(&version.key)
                .version_id(&version.version_id)
                .send(),
        )
        .await
        .context("Object Storage version deletion timed out")?
        .with_context(|| format!("Object Storage version DELETE failed for {}", version.key))?;
        Ok(())
    }

    fn validate_recording_object_key_subset(
        &self,
        base: &str,
        segment_count: u64,
        keys: &[String],
    ) -> anyhow::Result<()> {
        for key in keys {
            if key == &format!("{base}/COMMITTED") {
                continue;
            }
            let Some(suffix) = key.strip_prefix(&format!("{base}/segments/")) else {
                anyhow::bail!("recording object prefix contains an unexpected object");
            };
            let (sequence, extension) = suffix
                .split_once('.')
                .ok_or_else(|| anyhow::anyhow!("recording segment object name is invalid"))?;
            anyhow::ensure!(
                sequence.len() == 20
                    && sequence.chars().all(|character| character.is_ascii_digit())
                    && sequence.parse::<u64>()? < segment_count
                    && matches!(extension, "ndjson" | "COMMITTED"),
                "recording segment object name is outside the manifest"
            );
        }
        Ok(())
    }

    async fn validate_recording_object_keys(
        &self,
        base: &str,
        recording_id: &str,
        segment_count: u64,
        keys: &[String],
    ) -> anyhow::Result<()> {
        self.validate_recording_object_key_subset(base, segment_count, keys)?;
        for sequence in 0..segment_count {
            let object_key = format!("{base}/segments/{sequence:020}.ndjson");
            let marker_key = format!("{base}/segments/{sequence:020}.COMMITTED");
            anyhow::ensure!(
                keys.contains(&object_key) && keys.contains(&marker_key),
                "recording segment object pair is incomplete"
            );
            let marker_bytes = self.get(&marker_key).await?;
            let marker: StoredRecordingSegmentMarker = serde_json::from_slice(&marker_bytes)?;
            anyhow::ensure!(
                marker.recording_id == recording_id
                    && marker.segment_sequence == sequence
                    && marker.content_bytes > 0,
                "recording segment marker is invalid"
            );
            let metadata = tokio::time::timeout(
                self.operation_timeout,
                self.store.head(&Path::from(object_key.as_str())),
            )
            .await
            .context("Object Storage operation timed out")??;
            anyhow::ensure!(
                metadata.size == marker.content_bytes,
                "recording segment size does not match its commit marker"
            );
        }
        Ok(())
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
        let mut marker: StoredArchiveCommitMarker = serde_json::from_slice(&marker_bytes)?;
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
        if marker.archive_format == "TAR_ZSTD" {
            marker = self
                .migrate_legacy_archive(
                    request.tenant_id,
                    request.profile_id,
                    request.checkpoint_id,
                    &base,
                    &marker,
                )
                .await?;
        }
        anyhow::ensure!(
            marker.archive_format == "BROWSERCLOUD_PROFILE_AEAD_V1"
                && marker.archive_object == ENCRYPTED_ARCHIVE_OBJECT
                && marker.encryption_key_id.is_some()
                && marker.plaintext_archive_sha256.is_some()
                && marker.plaintext_archive_bytes.is_some(),
            "Profile archive is not application-layer encrypted"
        );
        let object_key = format!("{base}/{}", marker.archive_object);
        let object_path = Path::from(object_key.as_str());
        let metadata = tokio::time::timeout(self.operation_timeout, self.store.head(&object_path))
            .await
            .context("Object Storage operation timed out")?
            .with_context(|| format!("Object Storage HEAD failed for {object_key}"))?;
        anyhow::ensure!(
            metadata.size == marker.archive_bytes as u64,
            "Profile archive size does not match its commit marker"
        );

        // A signed export is a high-risk disclosure. Re-read, hash, authenticate and bind the
        // envelope identity immediately before issuing the URL. This prevents an Object Storage
        // writer from copying another tenant's valid ciphertext and marker into this object key.
        let encrypted_archive = self.get(&object_key).await?;
        let observed_bytes = encrypted_archive.len() as u64;
        let observed_sha256 = hex_sha256(&encrypted_archive);
        anyhow::ensure!(
            observed_bytes == marker.archive_bytes as u64
                && observed_sha256.eq_ignore_ascii_case(&marker.archive_sha256),
            "Profile archive integrity verification failed"
        );
        let decrypted = self.profile_crypto.decrypt(&encrypted_archive)?;
        let plaintext_sha256 = hex_sha256(decrypted.plaintext.as_slice());
        anyhow::ensure!(
            decrypted.tenant_id == request.tenant_id
                && decrypted.profile_id == request.profile_id
                && decrypted.checkpoint_id == request.checkpoint_id
                && marker.encryption_key_id.as_deref() == Some(decrypted.key_id.as_str())
                && marker.plaintext_archive_sha256.as_deref() == Some(plaintext_sha256.as_str())
                && marker.plaintext_archive_bytes == Some(decrypted.plaintext.len()),
            "Profile archive envelope identity does not match the export request"
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

    async fn migrate_legacy_archive(
        &self,
        tenant_id: &str,
        profile_id: &str,
        checkpoint_id: &str,
        base: &str,
        legacy_marker: &StoredArchiveCommitMarker,
    ) -> anyhow::Result<StoredArchiveCommitMarker> {
        anyhow::ensure!(
            legacy_marker.archive_object == LEGACY_ARCHIVE_OBJECT
                && legacy_marker.archive_format == "TAR_ZSTD"
                && legacy_marker.content_hash.len() == 64
                && legacy_marker
                    .content_hash
                    .chars()
                    .all(|character| character.is_ascii_hexdigit()),
            "legacy Profile archive marker is invalid"
        );
        let plaintext = self.get(&format!("{base}/{LEGACY_ARCHIVE_OBJECT}")).await?;
        anyhow::ensure!(
            plaintext.len() == legacy_marker.archive_bytes
                && hex_sha256(&plaintext).eq_ignore_ascii_case(&legacy_marker.archive_sha256),
            "legacy Profile archive integrity verification failed"
        );
        let plaintext_sha256 = hex_sha256(&plaintext);
        let encrypted =
            self.profile_crypto
                .encrypt(tenant_id, profile_id, checkpoint_id, &plaintext)?;
        let encrypted_sha256 = hex_sha256(&encrypted);
        self.put(
            &format!("{base}/{ENCRYPTED_ARCHIVE_OBJECT}"),
            Bytes::from(encrypted.clone()),
        )
        .await?;
        let committed = ArchiveCommitMarker {
            checkpoint_id,
            checkpoint_epoch: legacy_marker.checkpoint_epoch,
            profile_write_epoch: legacy_marker.profile_write_epoch,
            content_hash: &legacy_marker.content_hash,
            archive_sha256: encrypted_sha256.clone(),
            archive_bytes: encrypted.len(),
            archive_object: ENCRYPTED_ARCHIVE_OBJECT,
            archive_format: "BROWSERCLOUD_PROFILE_AEAD_V1",
            encryption_key_id: self.profile_crypto.active_key_id(),
            plaintext_archive_sha256: plaintext_sha256.clone(),
            plaintext_archive_bytes: plaintext.len(),
        };
        self.put(
            &format!("{base}/COMMITTED"),
            Bytes::from(serde_json::to_vec(&committed)?),
        )
        .await?;
        self.delete(&format!("{base}/{LEGACY_ARCHIVE_OBJECT}"))
            .await?;
        Ok(StoredArchiveCommitMarker {
            checkpoint_id: checkpoint_id.to_owned(),
            checkpoint_epoch: legacy_marker.checkpoint_epoch,
            profile_write_epoch: legacy_marker.profile_write_epoch,
            content_hash: legacy_marker.content_hash.clone(),
            archive_sha256: encrypted_sha256,
            archive_bytes: encrypted.len(),
            archive_object: ENCRYPTED_ARCHIVE_OBJECT.to_owned(),
            archive_format: "BROWSERCLOUD_PROFILE_AEAD_V1".to_owned(),
            encryption_key_id: Some(self.profile_crypto.active_key_id().to_owned()),
            plaintext_archive_sha256: Some(plaintext_sha256),
            plaintext_archive_bytes: Some(plaintext.len()),
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

    /// Create-only write used by immutable recording objects. A retry is accepted only when
    /// the existing bytes are identical; a conflicting overwrite fails closed.
    async fn put_immutable(&self, key: &str, payload: Bytes) -> anyhow::Result<()> {
        let result = tokio::time::timeout(
            self.operation_timeout,
            self.store.put_opts(
                &Path::from(key),
                PutPayload::from_bytes(payload.clone()),
                PutOptions {
                    mode: PutMode::Create,
                    ..Default::default()
                },
            ),
        )
        .await
        .context("Object Storage operation timed out")?;
        match result {
            Ok(_) => Ok(()),
            Err(ObjectStoreError::AlreadyExists { .. }) => {
                let existing = self.get(key).await?;
                anyhow::ensure!(
                    existing == payload,
                    "immutable Object Storage key already exists with different content: {key}"
                );
                Ok(())
            }
            Err(error) => Err(error)
                .with_context(|| format!("Object Storage create-only PUT failed for {key}")),
        }
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

    async fn get_optional(&self, key: &str) -> anyhow::Result<Option<Bytes>> {
        let result = tokio::time::timeout(self.operation_timeout, async {
            match self.store.get(&Path::from(key)).await {
                Ok(result) => result.bytes().await.map(Some),
                Err(ObjectStoreError::NotFound { .. }) => Ok(None),
                Err(error) => Err(error),
            }
        })
        .await
        .context("Object Storage operation timed out")?;
        result.with_context(|| format!("Object Storage optional GET failed for {key}"))
    }

    async fn delete(&self, key: &str) -> anyhow::Result<()> {
        tokio::time::timeout(self.operation_timeout, self.store.delete(&Path::from(key)))
            .await
            .context("Object Storage operation timed out")?
            .with_context(|| format!("Object Storage DELETE failed for {key}"))?;
        Ok(())
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

    fn recording_deletion_key_for(
        &self,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
        recording_id: &str,
    ) -> String {
        let suffix = format!(
            "tenants/{tenant_id}/profiles/{profile_id}/sessions/{session_id}/recording-deletions/{recording_id}"
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

fn recording_deletion_marker(
    request: &RecordingDeletionRequest<'_>,
    state: &str,
    completed_at_ms: Option<u64>,
) -> RecordingDeletionMarker {
    RecordingDeletionMarker {
        version: 1,
        state: state.to_owned(),
        deletion_job_id: request.deletion_job_id.to_owned(),
        deletion_epoch: request.deletion_epoch,
        tenant_id: request.tenant_id.to_owned(),
        profile_id: request.profile_id.to_owned(),
        session_id: request.session_id.to_owned(),
        recording_id: request.recording_id.to_owned(),
        manifest_sha256: request.manifest_sha256.to_ascii_lowercase(),
        manifest_bytes: request.manifest_bytes,
        segment_count: request.segment_count,
        deleted_object_count: request.segment_count.saturating_mul(2).saturating_add(1),
        completed_at_ms,
    }
}

fn validate_recording_deletion_marker(
    bytes: &[u8],
    request: &RecordingDeletionRequest<'_>,
    expected_state: &str,
) -> anyhow::Result<RecordingDeletionResult> {
    let marker: RecordingDeletionMarker = serde_json::from_slice(bytes)?;
    anyhow::ensure!(
        marker.version == 1
            && marker.state == expected_state
            && marker.deletion_job_id == request.deletion_job_id
            && marker.deletion_epoch > 0
            && marker.deletion_epoch <= request.deletion_epoch
            && marker.tenant_id == request.tenant_id
            && marker.profile_id == request.profile_id
            && marker.session_id == request.session_id
            && marker.recording_id == request.recording_id
            && marker
                .manifest_sha256
                .eq_ignore_ascii_case(request.manifest_sha256)
            && marker.manifest_bytes == request.manifest_bytes
            && marker.segment_count == request.segment_count
            && marker.deleted_object_count
                == request.segment_count.saturating_mul(2).saturating_add(1),
        "recording deletion marker does not match the authority request"
    );
    if expected_state == "COMMITTED" {
        let completed_at_ms = marker
            .completed_at_ms
            .filter(|value| *value > 0)
            .ok_or_else(|| anyhow::anyhow!("recording deletion marker omitted completion time"))?;
        Ok(RecordingDeletionResult {
            deletion_job_id: marker.deletion_job_id,
            recording_id: marker.recording_id,
            deletion_epoch: request.deletion_epoch,
            deletion_proof_hash: hex_sha256(bytes),
            deleted_object_count: marker.deleted_object_count,
            completed_at_ms,
        })
    } else {
        anyhow::ensure!(
            marker.completed_at_ms.is_none(),
            "prepared recording deletion marker contains completion time"
        );
        Ok(RecordingDeletionResult {
            deletion_job_id: marker.deletion_job_id,
            recording_id: marker.recording_id,
            deletion_epoch: request.deletion_epoch,
            deletion_proof_hash: String::new(),
            deleted_object_count: marker.deleted_object_count,
            completed_at_ms: 0,
        })
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
    pub profile_crypto: ProfileArchiveCrypto,
}

fn hex_sha256(value: &[u8]) -> String {
    format!("{:x}", Sha256::digest(value))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::os::unix::fs::PermissionsExt;
    use std::time::Instant;

    #[test]
    fn profile_archive_envelope_supports_rotation_and_rejects_tampering() {
        let old = ProfileArchiveCrypto::for_test("key-v1", &[("key-v1", [0x11; 32])]);
        let plaintext = b"Cookies=super-secret-session-value";
        let encrypted = old
            .encrypt("tenant-a", "profile-a", "chk_a", plaintext)
            .unwrap();
        assert!(ObjectArchive::is_encrypted_profile_archive(&encrypted));
        assert!(!encrypted
            .windows(plaintext.len())
            .any(|window| window == plaintext));

        let rotated = ProfileArchiveCrypto::for_test(
            "key-v2",
            &[("key-v1", [0x11; 32]), ("key-v2", [0x22; 32])],
        );
        let decrypted = rotated.decrypt(&encrypted).unwrap();
        assert_eq!(decrypted.plaintext.as_slice(), plaintext);
        assert_eq!(decrypted.key_id, "key-v1");

        let newly_encrypted = rotated
            .encrypt("tenant-a", "profile-a", "chk_b", plaintext)
            .unwrap();
        assert_eq!(rotated.decrypt(&newly_encrypted).unwrap().key_id, "key-v2");

        let mut tampered = encrypted.clone();
        *tampered.last_mut().unwrap() ^= 0x01;
        assert!(rotated.decrypt(&tampered).is_err());

        let missing_old_key = ProfileArchiveCrypto::for_test("key-v2", &[("key-v2", [0x22; 32])]);
        assert!(missing_old_key.decrypt(&encrypted).is_err());
    }

    #[test]
    fn profile_archive_keyring_requires_restricted_file_permissions() {
        let root = std::env::temp_dir().join(format!(
            "browsercloud-profile-keyring-test-{}",
            uuid::Uuid::new_v4().simple()
        ));
        fs::create_dir_all(&root).unwrap();
        let keyring_path = root.join("keyring.json");
        fs::write(
            &keyring_path,
            serde_json::json!({
                "activeKeyId": "key-v1",
                "keys": {"key-v1": BASE64.encode([0x31; 32])}
            })
            .to_string(),
        )
        .unwrap();
        fs::set_permissions(&keyring_path, fs::Permissions::from_mode(0o600)).unwrap();
        assert!(ProfileArchiveCrypto::from_keyring_file(&keyring_path).is_ok());

        fs::set_permissions(&keyring_path, fs::Permissions::from_mode(0o604)).unwrap();
        assert!(ProfileArchiveCrypto::from_keyring_file(&keyring_path).is_err());
        fs::remove_dir_all(root).unwrap();
    }

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
            profile_crypto: ProfileArchiveCrypto::for_test(
                "test-key-v1",
                &[("test-key-v1", [0x41; 32])],
            ),
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
                .get(&Path::from(format!("{base}/{ENCRYPTED_ARCHIVE_OBJECT}")))
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
            let plaintext_for_substitution = local.pack_checkpoint(&manifest).await.unwrap();
            let substituted = archive
                .profile_crypto
                .encrypt(
                    "tenant-other",
                    "profile-test",
                    &manifest.checkpoint_id,
                    &plaintext_for_substitution,
                )
                .unwrap();
            let substituted_marker = ArchiveCommitMarker {
                checkpoint_id: &manifest.checkpoint_id,
                checkpoint_epoch: manifest.checkpoint_epoch,
                profile_write_epoch: manifest.profile_write_epoch,
                content_hash: &manifest.content_hash,
                archive_sha256: hex_sha256(&substituted),
                archive_bytes: substituted.len(),
                archive_object: ENCRYPTED_ARCHIVE_OBJECT,
                archive_format: "BROWSERCLOUD_PROFILE_AEAD_V1",
                encryption_key_id: archive.profile_crypto.active_key_id(),
                plaintext_archive_sha256: hex_sha256(&plaintext_for_substitution),
                plaintext_archive_bytes: plaintext_for_substitution.len(),
            };
            archive
                .put(
                    &format!("{base}/{ENCRYPTED_ARCHIVE_OBJECT}"),
                    Bytes::from(substituted),
                )
                .await
                .unwrap();
            archive
                .put(
                    &format!("{base}/COMMITTED"),
                    Bytes::from(serde_json::to_vec(&substituted_marker).unwrap()),
                )
                .await
                .unwrap();
            assert!(archive
                .sign_profile_export_download(ProfileExportDownloadRequest {
                    tenant_id: "tenant-test",
                    profile_id: "profile-test",
                    checkpoint_id: &manifest.checkpoint_id,
                    expires_in: Duration::from_secs(60),
                })
                .await
                .is_err());
            archive.commit_checkpoint(&local, &manifest).await.unwrap();
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
            assert!(ObjectArchive::is_encrypted_profile_archive(
                &downloaded_export
            ));
            let decrypted_export = archive.decrypt_profile_import(&downloaded_export).unwrap();
            assert!(!ObjectArchive::is_encrypted_profile_archive(
                &decrypted_export
            ));
            archive
                .put(
                    &format!("{base}/{LEGACY_ARCHIVE_OBJECT}"),
                    Bytes::from(decrypted_export.clone()),
                )
                .await
                .unwrap();
            archive
                .put(
                    &format!("{base}/COMMITTED"),
                    Bytes::from(
                        serde_json::to_vec(&serde_json::json!({
                            "checkpointId": manifest.checkpoint_id,
                            "checkpointEpoch": manifest.checkpoint_epoch,
                            "profileWriteEpoch": manifest.profile_write_epoch,
                            "contentHash": manifest.content_hash,
                            "archiveSha256": hex_sha256(&decrypted_export),
                            "archiveBytes": decrypted_export.len()
                        }))
                        .unwrap(),
                    ),
                )
                .await
                .unwrap();
            archive
                .delete(&format!("{base}/{ENCRYPTED_ARCHIVE_OBJECT}"))
                .await
                .unwrap();
            let restored_root = std::env::temp_dir().join(format!(
                "browsercloud-object-archive-legacy-restore-test-{}",
                uuid::Uuid::new_v4().simple()
            ));
            let restored_local = LocalProfileStore::open(restored_root.clone())
                .await
                .unwrap();
            archive
                .restore_checkpoint(
                    &restored_local,
                    "tenant-test",
                    "profile-test",
                    &manifest.checkpoint_id,
                )
                .await
                .unwrap();
            assert!(archive
                .store
                .head(&Path::from(format!("{base}/{LEGACY_ARCHIVE_OBJECT}")))
                .await
                .is_err());
            let migrated = archive
                .store
                .get(&Path::from(format!("{base}/{ENCRYPTED_ARCHIVE_OBJECT}")))
                .await
                .unwrap()
                .bytes()
                .await
                .unwrap();
            assert!(ObjectArchive::is_encrypted_profile_archive(&migrated));
            let encrypted_restore_root = std::env::temp_dir().join(format!(
                "browsercloud-object-archive-encrypted-restore-test-{}",
                uuid::Uuid::new_v4().simple()
            ));
            let encrypted_restore_local = LocalProfileStore::open(encrypted_restore_root.clone())
                .await
                .unwrap();
            archive
                .restore_checkpoint(
                    &encrypted_restore_local,
                    "tenant-test",
                    "profile-test",
                    &manifest.checkpoint_id,
                )
                .await
                .unwrap();
            fs::remove_dir_all(restored_root).unwrap();
            fs::remove_dir_all(encrypted_restore_root).unwrap();
            let recording_content = Bytes::from_static(
                br#"{"capturedAtMs":1,"cdpSessionId":7,"format":"jpeg","redactionState":"MASKED","redactedRegionCount":2,"redactionPolicyVersion":1,"data":"/9j/"}"#,
            );
            let recording_hash = hex_sha256(&recording_content);
            let segment_key = archive
                .commit_recording_segment(
                    "tenant-test",
                    "profile-test",
                    "session-test",
                    "rec-test",
                    0,
                    recording_content.clone(),
                    &recording_hash,
                    1,
                    1,
                    2,
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
            let completed = archive
                .complete_recording(
                    "tenant-test",
                    "profile-test",
                    "session-test",
                    "rec-test",
                    1,
                    1,
                    1,
                    2,
                    1,
                    1,
                    2,
                )
                .await
                .unwrap();
            let manifest = archive
                .store
                .get(&Path::from(completed.object_key.as_str()))
                .await
                .unwrap()
                .bytes()
                .await
                .unwrap();
            assert_eq!(completed.manifest_sha256, hex_sha256(&manifest));
            assert_eq!(completed.manifest_bytes, manifest.len() as u64);
            let rejected_playback = archive
                .sign_recording_playback(RecordingPlaybackRequest {
                    tenant_id: "tenant-test",
                    profile_id: "profile-test",
                    session_id: "session-test",
                    recording_id: "rec-test",
                    manifest_sha256: &"0".repeat(64),
                    manifest_bytes: completed.manifest_bytes,
                    segment_count: 1,
                    frame_count: 1,
                    redacted_frame_count: 1,
                    redacted_region_count: 2,
                    redaction_policy_version: 1,
                    started_at_ms: 1,
                    ended_at_ms: 2,
                    segment_offset: 0,
                    segment_limit: 24,
                    expires_in: Duration::from_secs(60),
                })
                .await;
            assert!(rejected_playback.is_err());
            let playback = archive
                .sign_recording_playback(RecordingPlaybackRequest {
                    tenant_id: "tenant-test",
                    profile_id: "profile-test",
                    session_id: "session-test",
                    recording_id: "rec-test",
                    manifest_sha256: &completed.manifest_sha256,
                    manifest_bytes: completed.manifest_bytes,
                    segment_count: 1,
                    frame_count: 1,
                    redacted_frame_count: 1,
                    redacted_region_count: 2,
                    redaction_policy_version: 1,
                    started_at_ms: 1,
                    ended_at_ms: 2,
                    segment_offset: 0,
                    segment_limit: 24,
                    expires_in: Duration::from_secs(60),
                })
                .await
                .unwrap();
            assert_eq!(playback.segments.len(), 1);
            assert_eq!(playback.segments[0].content_sha256, recording_hash);
            assert!(playback.next_segment_offset.is_none());
            let downloaded_recording = reqwest::get(&playback.segments[0].download_url)
                .await
                .unwrap()
                .error_for_status()
                .unwrap()
                .bytes()
                .await
                .unwrap();
            assert_eq!(downloaded_recording.as_ref(), recording_content.as_ref());
            let deletion = archive
                .delete_recording(RecordingDeletionRequest {
                    deletion_job_id: "rrd-test",
                    deletion_epoch: 1,
                    tenant_id: "tenant-test",
                    profile_id: "profile-test",
                    session_id: "session-test",
                    recording_id: "rec-test",
                    manifest_sha256: &completed.manifest_sha256,
                    manifest_bytes: completed.manifest_bytes,
                    segment_count: 1,
                })
                .await
                .unwrap();
            assert_eq!(deletion.deleted_object_count, 3);
            assert_eq!(deletion.deletion_proof_hash.len(), 64);
            assert!(archive
                .recording_object_keys(&recording_base)
                .await
                .unwrap()
                .is_empty());
            let repeated = archive
                .delete_recording(RecordingDeletionRequest {
                    deletion_job_id: "rrd-test",
                    deletion_epoch: 2,
                    tenant_id: "tenant-test",
                    profile_id: "profile-test",
                    session_id: "session-test",
                    recording_id: "rec-test",
                    manifest_sha256: &completed.manifest_sha256,
                    manifest_bytes: completed.manifest_bytes,
                    segment_count: 1,
                })
                .await
                .unwrap();
            assert_eq!(repeated.deletion_proof_hash, deletion.deletion_proof_hash);
            assert_eq!(repeated.completed_at_ms, deletion.completed_at_ms);
            assert_eq!(repeated.deletion_epoch, 2);
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
