use anyhow::{anyhow, Result};
use ops_workers::{
    read_private_secret, run_validation_once, OpsControlPlaneClient, ValidationConfig,
};
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::time::Duration;
use tracing_subscriber::EnvFilter;

fn flag(args: &[String], name: &str) -> Option<String> {
    args.windows(2).find(|w| w[0] == name).map(|w| w[1].clone())
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .compact()
        .init();

    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.is_empty() || args.iter().any(|a| a == "-h" || a == "--help") {
        println!(
            "browsercloud-validation-worker (pure Rust)\n\
             Flags: --control-plane-url --control-plane-token-file --runner --browser-version [--browser-engine chromium] [--environment production|local|test] [--once]"
        );
        return Ok(());
    }
    let cp_url = flag(&args, "--control-plane-url")
        .ok_or_else(|| anyhow!("--control-plane-url required"))?;
    let cp_tok_file = flag(&args, "--control-plane-token-file")
        .ok_or_else(|| anyhow!("--control-plane-token-file required"))?;
    let runner = flag(&args, "--runner").ok_or_else(|| anyhow!("--runner required"))?;
    let worker_id = flag(&args, "--worker-id").unwrap_or_else(|| "rust-validation-1".to_owned());
    let environment = flag(&args, "--environment").unwrap_or_else(|| "production".to_owned());
    let once = args.iter().any(|a| a == "--once");

    let token = read_private_secret(&PathBuf::from(cp_tok_file))?;
    let cp_ca = flag(&args, "--control-plane-ca-file").map(PathBuf::from);
    let client = OpsControlPlaneClient::new(
        cp_url,
        token,
        cp_ca.as_deref(),
        environment,
        worker_id,
        "VALIDATION_WORKER",
        "agent-browser-cloud-validation-worker/1-rust",
    )?;
    let versions: Vec<String> = flag(&args, "--browser-version")
        .unwrap_or_else(|| "156".to_owned())
        .split(',')
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty())
        .collect();
    let cfg = ValidationConfig {
        browser_engine: flag(&args, "--browser-engine").unwrap_or_else(|| "chromium".to_owned()),
        browser_versions: versions,
        operating_system: std::env::consts::OS.to_owned(),
        architecture: std::env::consts::ARCH.to_owned(),
        capabilities: BTreeMap::from([("cdp".to_owned(), true), ("headless".to_owned(), true)]),
        runner_command: vec![runner],
        runner_timeout_seconds: flag(&args, "--runner-timeout-seconds")
            .and_then(|s| s.parse().ok())
            .unwrap_or(600),
        heartbeat_seconds: flag(&args, "--heartbeat-seconds")
            .and_then(|s| s.parse().ok())
            .unwrap_or(15.0),
        idle_seconds: flag(&args, "--idle-seconds")
            .and_then(|s| s.parse().ok())
            .unwrap_or(2.0),
    };

    loop {
        let worked = run_validation_once(&client, &cfg).await?;
        if once {
            return Ok(());
        }
        if !worked {
            tokio::time::sleep(Duration::from_secs_f64(cfg.idle_seconds.clamp(0.1, 60.0))).await;
        }
    }
}
