use anyhow::{anyhow, bail, ensure, Result};
use application_adapter::{
    entity_identity_attributes, read_secret_file, BrowserCloudAdapterClient, HttpTransport,
    ProviderAttestationSpec,
};
use serde_json::json;
use std::path::PathBuf;

fn flag(args: &[String], name: &str) -> Option<String> {
    args.windows(2).find(|w| w[0] == name).map(|w| w[1].clone())
}

fn flags_all(args: &[String], name: &str) -> Vec<String> {
    args.windows(2)
        .filter(|w| w[0] == name)
        .map(|w| w[1].clone())
        .collect()
}

fn has_flag(args: &[String], name: &str) -> bool {
    args.iter().any(|a| a == name)
}

fn allow_http(args: &[String]) -> Result<bool> {
    if !has_flag(args, "--allow-insecure-http") {
        return Ok(false);
    }
    let env = std::env::var("APP_ENVIRONMENT").unwrap_or_default();
    ensure!(
        matches!(env.to_ascii_lowercase().as_str(), "local" | "test"),
        "INSECURE_HTTP_FORBIDDEN"
    );
    Ok(true)
}

fn build_cp_client(args: &[String]) -> Result<BrowserCloudAdapterClient> {
    let cp_url =
        flag(args, "--control-plane-url").ok_or_else(|| anyhow!("CONTROL_PLANE_URL_REQUIRED"))?;
    let tok_file = flag(args, "--control-plane-token-file")
        .ok_or_else(|| anyhow!("CONTROL_PLANE_TOKEN_FILE_REQUIRED"))?;
    let ca_file = flag(args, "--control-plane-ca-file").map(PathBuf::from);
    let timeout = flag(args, "--timeout-seconds")
        .and_then(|s| s.parse::<f64>().ok())
        .unwrap_or(10.0);
    let uri: http::Uri = cp_url
        .parse()
        .map_err(|_| anyhow!("CONTROL_PLANE_URL_REJECTED"))?;
    let host = uri
        .authority()
        .map(|a| a.host().to_owned())
        .unwrap_or_default();
    let transport = HttpTransport::new([host], timeout, ca_file.as_deref(), allow_http(args)?)?;
    let tenant = flag(args, "--local-tenant-id");
    let actor = flag(args, "--local-actor-id");
    let local_identity = match (tenant, actor) {
        (Some(t), Some(a)) => {
            let env = std::env::var("APP_ENVIRONMENT").unwrap_or_default();
            ensure!(
                matches!(env.to_ascii_lowercase().as_str(), "local" | "test"),
                "LOCAL_IDENTITY_FORBIDDEN"
            );
            Some((t, a))
        }
        (None, None) => None,
        _ => bail!("LOCAL_IDENTITY_INCOMPLETE"),
    };
    Ok(BrowserCloudAdapterClient::new(
        cp_url,
        transport,
        read_secret_file(&PathBuf::from(tok_file))?,
        local_identity,
    ))
}

async fn run() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.is_empty() || matches!(args[0].as_str(), "-h" | "--help") {
        println!(
            "browsercloud-application-adapter (pure Rust)\n\
             Commands: attest | entity-identity | lease-acquire | lease-renew | lease-release"
        );
        return Ok(());
    }
    let cmd = args[0].as_str();
    let rest = &args[1..];
    match cmd {
        "entity-identity" => {
            let val_file = flag(rest, "--entity-value-file")
                .ok_or_else(|| anyhow!("ENTITY_IDENTITY_INPUT_INVALID"))?;
            let key_file = flag(rest, "--identity-key-file")
                .ok_or_else(|| anyhow!("ENTITY_IDENTITY_INPUT_INVALID"))?;
            let ns = flag(rest, "--namespace")
                .ok_or_else(|| anyhow!("ENTITY_IDENTITY_INPUT_INVALID"))?;
            let et = flag(rest, "--entity-type")
                .ok_or_else(|| anyhow!("ENTITY_IDENTITY_INPUT_INVALID"))?;
            let attrs = entity_identity_attributes(
                &read_secret_file(&PathBuf::from(val_file))?,
                &ns,
                &et,
                &read_secret_file(&PathBuf::from(key_file))?,
            )?;
            println!("{}", serde_json::to_string(&attrs)?);
        }
        "attest" => {
            let cp = build_cp_client(rest)?;
            let timeout = flag(rest, "--timeout-seconds")
                .and_then(|s| s.parse::<f64>().ok())
                .unwrap_or(10.0);
            let p_ca = flag(rest, "--provider-ca-file").map(PathBuf::from);
            let p_transport = HttpTransport::new(
                flags_all(rest, "--provider-host"),
                timeout,
                p_ca.as_deref(),
                allow_http(rest)?,
            )?;
            let p_tok = match flag(rest, "--provider-token-file") {
                Some(f) => Some(read_secret_file(&PathBuf::from(f))?),
                None => None,
            };
            let spec = ProviderAttestationSpec {
                session_id: flag(rest, "--session-id").unwrap_or_default(),
                context_epoch: flag(rest, "--context-epoch")
                    .and_then(|s| s.parse().ok())
                    .unwrap_or(0),
                state_version: flag(rest, "--state-version")
                    .and_then(|s| s.parse().ok())
                    .unwrap_or(0),
                evidence_type: flag(rest, "--evidence-type").unwrap_or_default(),
                key: flag(rest, "--key").unwrap_or_default(),
                provider_id: flag(rest, "--provider-id").unwrap_or_default(),
                expected_value_hash: flag(rest, "--expected-value-hash").unwrap_or_default(),
                provider_url: flag(rest, "--provider-url").unwrap_or_default(),
                value_pointer: flag(rest, "--value-pointer").unwrap_or_default(),
                reference_header: flag(rest, "--provider-reference-header")
                    .unwrap_or_else(|| "x-request-id".to_owned()),
            };
            let receipt = cp
                .run_attestation(&p_transport, p_tok.as_deref(), &spec)
                .await?;
            println!("{}", serde_json::to_string(&receipt)?);
        }
        "lease-acquire" => {
            let cp = build_cp_client(rest)?;
            let res = cp
                .acquire_lease(
                    &flag(rest, "--session-id").unwrap_or_default(),
                    &flag(rest, "--signal-type").unwrap_or_default(),
                    &flag(rest, "--reason-code").unwrap_or_default(),
                    flag(rest, "--ttl-seconds")
                        .and_then(|s| s.parse().ok())
                        .unwrap_or(60),
                    &flag(rest, "--idempotency-key").unwrap_or_default(),
                )
                .await?;
            println!("{}", serde_json::to_string(&res)?);
        }
        "lease-renew" => {
            let cp = build_cp_client(rest)?;
            let res = cp
                .renew_lease(
                    &flag(rest, "--session-id").unwrap_or_default(),
                    &flag(rest, "--lease-id").unwrap_or_default(),
                    flag(rest, "--ttl-seconds")
                        .and_then(|s| s.parse().ok())
                        .unwrap_or(60),
                    &flag(rest, "--idempotency-key").unwrap_or_default(),
                )
                .await?;
            println!("{}", serde_json::to_string(&res)?);
        }
        "lease-release" => {
            let cp = build_cp_client(rest)?;
            let res = cp
                .release_lease(
                    &flag(rest, "--session-id").unwrap_or_default(),
                    &flag(rest, "--lease-id").unwrap_or_default(),
                    &flag(rest, "--idempotency-key").unwrap_or_default(),
                )
                .await?;
            println!("{}", serde_json::to_string(&res)?);
        }
        other => bail!("UNKNOWN_COMMAND: {other}"),
    }
    Ok(())
}

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprintln!("{}", json!({ "error": err.to_string() }));
        std::process::exit(2);
    }
}
