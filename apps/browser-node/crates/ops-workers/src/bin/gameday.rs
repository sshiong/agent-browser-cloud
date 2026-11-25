use anyhow::{anyhow, Result};
use ops_workers::{
    read_private_secret, require_absolute_executable, run_gameday_once, GameDayConfig,
    OpsControlPlaneClient,
};
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
            "browsercloud-gameday-worker (pure Rust)\n\
             Flags: --control-plane-url --control-plane-token-file --controller-token-file --catalog-file --runner --worker-id [--environment production|local|test] [--once]"
        );
        return Ok(());
    }
    let cp_url = flag(&args, "--control-plane-url")
        .ok_or_else(|| anyhow!("--control-plane-url required"))?;
    let cp_tok_file = flag(&args, "--control-plane-token-file")
        .ok_or_else(|| anyhow!("--control-plane-token-file required"))?;
    let ctrl_tok_file = PathBuf::from(
        flag(&args, "--controller-token-file")
            .ok_or_else(|| anyhow!("--controller-token-file required"))?,
    );
    let catalog_file = PathBuf::from(
        flag(&args, "--catalog-file").ok_or_else(|| anyhow!("--catalog-file required"))?,
    );
    let runner = require_absolute_executable(&PathBuf::from(
        flag(&args, "--runner").ok_or_else(|| anyhow!("--runner required"))?,
    ))?;
    let worker_id = flag(&args, "--worker-id").ok_or_else(|| anyhow!("--worker-id required"))?;
    let environment = flag(&args, "--environment").unwrap_or_else(|| "production".to_owned());
    let poll_seconds = flag(&args, "--poll-seconds")
        .and_then(|s| s.parse::<f64>().ok())
        .unwrap_or(5.0);
    let heartbeat_seconds = flag(&args, "--heartbeat-seconds")
        .and_then(|s| s.parse::<f64>().ok())
        .unwrap_or(15.0);
    let once = args.iter().any(|a| a == "--once");

    let token = read_private_secret(&PathBuf::from(cp_tok_file))?;
    let _ = read_private_secret(&ctrl_tok_file)?;
    let cp_ca = flag(&args, "--control-plane-ca-file").map(PathBuf::from);
    let client = OpsControlPlaneClient::new(
        cp_url,
        token,
        cp_ca.as_deref(),
        environment,
        worker_id,
        "GAMEDAY_WORKER",
        "agent-browser-cloud-gameday-worker/1-rust",
    )?;
    let cfg = GameDayConfig {
        catalog_file,
        controller_token_file: ctrl_tok_file,
        controller_ca_file: flag(&args, "--controller-ca-file").map(PathBuf::from),
        runner,
        poll_seconds,
        heartbeat_seconds,
    };

    loop {
        let worked = run_gameday_once(&client, &cfg).await?;
        if once {
            return Ok(());
        }
        if !worked {
            tokio::time::sleep(Duration::from_secs_f64(poll_seconds.clamp(1.0, 60.0))).await;
        }
    }
}
