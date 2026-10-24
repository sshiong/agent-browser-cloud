//! Read-only S3 oracle for the owned loopback Recording fixture, independent of executor proofs.
use anyhow::{ensure, Result};
use aws_sdk_s3::config::{BehaviorVersion, Credentials, Region};
use aws_sdk_s3::error::ProvideErrorMetadata;
use std::collections::BTreeSet;
use std::time::Duration;

async fn verify() -> Result<()> {
    let args = std::env::args().skip(1).collect::<Vec<_>>();
    ensure!(args.len() == 6, "owned oracle arguments required");
    let port = args[0]
        .strip_prefix("http://127.0.0.1:")
        .ok_or_else(|| anyhow::anyhow!("loopback only"))?
        .parse::<u16>()?;
    ensure!(
        port > 0 && matches!(args[3].as_str(), "ON" | "OFF" | "EMPTY"),
        "invalid scope"
    );
    let expected_objects = args[4].parse::<usize>()?;
    let expected_versions = args[5].parse::<usize>()?;
    ensure!(
        (expected_objects == 51 && expected_versions == 52 && args[3] != "EMPTY")
            || (expected_objects == 0 && expected_versions == 0 && args[3] == "EMPTY"),
        "owned fixture only"
    );
    ensure!(
        args[2].starts_with("tenants/tenant-recording-native/")
            && (args[2].ends_with("/recordings/rec_22222222222222222222222222222222/")
                || args[2].ends_with("/recordings/rec_33333333333333333333333333333333/")),
        "owned scope only"
    );
    let config = aws_sdk_s3::Config::builder()
        .behavior_version(BehaviorVersion::latest())
        .region(Region::new("us-east-1"))
        .endpoint_url(&args[0])
        .force_path_style(true)
        .credentials_provider(Credentials::new(
            std::env::var("TEST_OBJECT_STORAGE_ACCESS_KEY_ID")?,
            std::env::var("TEST_OBJECT_STORAGE_SECRET_ACCESS_KEY")?,
            None,
            None,
            "owned-hold-oracle",
        ))
        .retry_config(aws_sdk_s3::config::retry::RetryConfig::standard().with_max_attempts(1))
        .build();
    let client = aws_sdk_s3::Client::from_conf(config);
    let inventory = client
        .list_object_versions()
        .bucket(&args[1])
        .prefix(&args[2])
        .send()
        .await?;
    ensure!(
        inventory.is_truncated() != Some(true) && inventory.delete_markers().is_empty(),
        "incomplete inventory"
    );
    ensure!(
        inventory.versions().len() == expected_versions,
        "version count mismatch"
    );
    let lock = client
        .get_object_lock_configuration()
        .bucket(&args[1])
        .send()
        .await?;
    ensure!(
        lock.object_lock_configuration()
            .and_then(|value| value.object_lock_enabled())
            == Some(&aws_sdk_s3::types::ObjectLockEnabled::Enabled),
        "bucket lock disabled"
    );
    let mut keys = BTreeSet::new();
    for version in inventory.versions() {
        let key = version
            .key()
            .ok_or_else(|| anyhow::anyhow!("missing key"))?;
        let id = version
            .version_id()
            .ok_or_else(|| anyhow::anyhow!("missing version"))?;
        ensure!(
            key.starts_with(&args[2]) && !id.is_empty() && id != "null",
            "unbound version"
        );
        keys.insert(key.to_owned());
        match client
            .get_object_legal_hold()
            .bucket(&args[1])
            .key(key)
            .version_id(id)
            .send()
            .await
        {
            Ok(hold) => ensure!(
                hold.legal_hold()
                    .and_then(|value| value.status())
                    .map(|value| value.as_str())
                    == Some(args[3].as_str()),
                "native state mismatch"
            ),
            Err(error)
                if args[3] == "OFF"
                    && error.as_service_error().and_then(|value| value.code())
                        == Some("NoSuchObjectLockConfiguration") =>
            {
                // MinIO's absent OFF metadata is valid only with a Lock-enabled bucket and
                // a successful HEAD of this exact retained version; arbitrary 404 is not OFF.
                let head = client
                    .head_object()
                    .bucket(&args[1])
                    .key(key)
                    .version_id(id)
                    .send()
                    .await?;
                ensure!(head.version_id() == Some(id), "missing version");
            }
            Err(_) => anyhow::bail!("native hold readback failed"),
        }
    }
    ensure!(keys.len() == expected_objects, "object count mismatch");
    println!(
        "recording_native_hold={} objects={} versions={}",
        args[3],
        keys.len(),
        expected_versions
    );
    Ok(())
}

#[tokio::main]
async fn main() {
    if !matches!(
        tokio::time::timeout(Duration::from_secs(30), verify()).await,
        Ok(Ok(()))
    ) {
        eprintln!("RECORDING_NATIVE_HOLD_ORACLE_REJECTED");
        std::process::exit(1);
    }
}
