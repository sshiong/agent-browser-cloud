use anyhow::{bail, Result};
use std::path::PathBuf;
use tokio::sync::watch;
use tracing_subscriber::EnvFilter;
use worker_supervisor::{
    read_secret_file, run_supervisor, ModelConfig, RoleRuntimeConfig, SupervisorConfig, WorkerRole,
};

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_owned())
}

fn env_opt(key: &str) -> Option<String> {
    std::env::var(key).ok().filter(|v| !v.trim().is_empty())
}

fn load_role_token(role: WorkerRole) -> Result<String> {
    let specific_file = match role {
        WorkerRole::Agent => env_opt("AGENT_CONTROL_PLANE_TOKEN_FILE"),
        WorkerRole::Reviewer => env_opt("REVIEWER_CONTROL_PLANE_TOKEN_FILE"),
        WorkerRole::Outcome => env_opt("OUTCOME_CONTROL_PLANE_TOKEN_FILE"),
        WorkerRole::Vision => env_opt("VISION_CONTROL_PLANE_TOKEN_FILE"),
    }
    .or_else(|| env_opt("CONTROL_PLANE_TOKEN_FILE"));
    let Some(path) = specific_file else {
        bail!("missing token file for role {}", role.as_str());
    };
    read_secret_file(&PathBuf::from(path))
}

fn load_model_config(role: WorkerRole) -> Result<ModelConfig> {
    let prefix = role.as_str().to_ascii_uppercase();
    let endpoint = env_opt(&format!("{prefix}_MODEL_ENDPOINT"))
        .or_else(|| env_opt("MODEL_ENDPOINT"))
        .ok_or_else(|| anyhow::anyhow!("MODEL_ENDPOINT required for {}", role.as_str()))?;
    let key_file = env_opt(&format!("{prefix}_MODEL_API_KEY_FILE"))
        .or_else(|| env_opt("MODEL_API_KEY_FILE"))
        .ok_or_else(|| anyhow::anyhow!("MODEL_API_KEY_FILE required for {}", role.as_str()))?;
    let api_key = read_secret_file(&PathBuf::from(key_file))?;
    let deployment_id = env_opt(&format!("{prefix}_DEPLOYMENT_ID"))
        .unwrap_or_else(|| format!("local-{}-default", role.as_str()));
    let model_name = env_opt(&format!("{prefix}_MODEL_NAME"))
        .or_else(|| env_opt("MODEL_NAME"))
        .unwrap_or_else(|| "gpt-4o-mini".to_owned());
    let model_revision = env_opt(&format!("{prefix}_MODEL_REVISION"))
        .or_else(|| env_opt("MODEL_REVISION"))
        .unwrap_or_else(|| "2026-01".to_owned());
    let expected_response_model = env_opt(&format!("{prefix}_EXPECTED_RESPONSE_MODEL"))
        .or_else(|| env_opt("EXPECTED_RESPONSE_MODEL"));
    let allowed_model_hosts = env_opt("ALLOWED_MODEL_HOSTS")
        .map(|s| s.split(',').map(str::trim).map(ToOwned::to_owned).collect())
        .unwrap_or_default();
    let allowed_screenshot_hosts = env_opt("ALLOWED_SCREENSHOT_HOSTS")
        .map(|s| s.split(',').map(str::trim).map(ToOwned::to_owned).collect())
        .unwrap_or_default();
    let maximum_output_tokens = env_opt("MODEL_MAX_OUTPUT_TOKENS")
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(512);
    let timeout_seconds = env_opt("MODEL_TIMEOUT_SECONDS")
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(120);

    Ok(ModelConfig {
        deployment_id,
        model_endpoint: endpoint,
        model_api_key: api_key,
        model_ca_file: env_opt("MODEL_CA_FILE").map(PathBuf::from),
        model_name,
        expected_response_model,
        model_revision,
        allowed_model_hosts,
        allowed_screenshot_hosts,
        maximum_output_tokens,
        timeout_seconds,
        skip_local_ocr_for_tests: false,
    })
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .compact()
        .init();

    let mut roles_csv = env_or("WORKER_ROLES", "agent,reviewer,outcome,vision");
    let mut control_plane_url = env_or("CONTROL_PLANE_URL", "http://127.0.0.1:8080");
    let mut environment = env_or("WORKER_ENVIRONMENT", "production");
    let mut ready_file = env_opt("WORKER_READY_FILE").map(PathBuf::from);
    let mut poll_seconds = env_opt("WORKER_POLL_SECONDS")
        .and_then(|s| s.parse::<f64>().ok())
        .unwrap_or(2.0);
    let mut heartbeat_seconds = env_opt("WORKER_HEARTBEAT_SECONDS")
        .and_then(|s| s.parse::<f64>().ok())
        .unwrap_or(15.0);
    let mut once = false;

    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--roles" => roles_csv = args.next().unwrap_or_default(),
            "--control-plane-url" => control_plane_url = args.next().unwrap_or_default(),
            "--environment" => environment = args.next().unwrap_or_default(),
            "--ready-file" => ready_file = args.next().map(PathBuf::from),
            "--poll-seconds" => {
                poll_seconds = args
                    .next()
                    .and_then(|s| s.parse::<f64>().ok())
                    .unwrap_or(poll_seconds);
            }
            "--heartbeat-seconds" => {
                heartbeat_seconds = args
                    .next()
                    .and_then(|s| s.parse::<f64>().ok())
                    .unwrap_or(heartbeat_seconds);
            }
            "--once" => once = true,
            "-h" | "--help" => {
                println!(
                    "browsercloud-worker-supervisor — Pure-Rust unified Agent/Reviewer/Outcome/Vision worker\n\
                     Flags:\n  --roles agent,reviewer,outcome,vision\n  --control-plane-url URL\n  --environment production|local|test\n  --ready-file /tmp/worker-ready\n  --once"
                );
                return Ok(());
            }
            other => bail!("unknown flag: {other}"),
        }
    }

    let mut roles = Vec::new();
    for raw_role in roles_csv.split(',').filter(|s| !s.trim().is_empty()) {
        let role = WorkerRole::parse(raw_role)?;
        let prefix = role.as_str().to_ascii_uppercase();
        let worker_id = env_opt(&format!("{prefix}_WORKER_ID"))
            .unwrap_or_else(|| format!("rust-{}-worker-1", role.as_str()));
        let concurrency = env_opt(&format!("{prefix}_CONCURRENCY"))
            .and_then(|s| s.parse::<usize>().ok())
            .unwrap_or(2);
        let token = load_role_token(role)?;
        let model = match role {
            WorkerRole::Agent => None,
            _ => Some(load_model_config(role)?),
        };
        roles.push(RoleRuntimeConfig {
            role,
            worker_id,
            control_plane_token: token,
            concurrency,
            model,
        });
    }

    let cfg = SupervisorConfig {
        control_plane_url,
        control_plane_ca_file: env_opt("CONTROL_PLANE_CA_FILE").map(PathBuf::from),
        environment,
        poll_seconds,
        heartbeat_seconds,
        ready_file,
        once,
        roles,
    };

    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    tokio::spawn(async move {
        let _ = tokio::signal::ctrl_c().await;
        let _ = shutdown_tx.send(true);
    });

    run_supervisor(cfg, shutdown_rx).await?;
    Ok(())
}
