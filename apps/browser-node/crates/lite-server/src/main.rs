//! `browsercloud-lite`: single-binary pure-Rust L0 Lite Agent Browser Cloud server.

use anyhow::{Context, Result};
use lite_server::{build_router, LiteConfig, LiteRuntime, LiteStore};
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tracing_subscriber::EnvFilter;

fn run_healthcheck(port: u16) -> Result<()> {
    let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port);
    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(3))
        .with_context(|| format!("healthcheck connect failed on {addr}"))?;
    stream.set_read_timeout(Some(Duration::from_secs(3)))?;
    stream.set_write_timeout(Some(Duration::from_secs(3)))?;
    write!(
        stream,
        "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n"
    )?;
    stream.flush()?;
    let mut body = String::new();
    stream.read_to_string(&mut body)?;
    let first_line = body.lines().next().unwrap_or_default();
    anyhow::ensure!(
        first_line.starts_with("HTTP/1.1 200") || first_line.starts_with("HTTP/1.0 200"),
        "unexpected healthcheck status: {first_line}"
    );
    anyhow::ensure!(
        body.contains("\"status\":\"UP\""),
        "healthcheck payload did not report UP"
    );
    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
    if std::env::args().nth(1).as_deref() == Some("--healthcheck") {
        let port = std::env::var("LITE_BIND_ADDR")
            .ok()
            .and_then(|raw| raw.parse::<SocketAddr>().ok().map(|s| s.port()))
            .or_else(|| {
                std::env::var("LITE_PORT")
                    .ok()
                    .and_then(|v| v.parse::<u16>().ok())
            })
            .unwrap_or(8130);
        return run_healthcheck(port);
    }

    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let bind_addr: SocketAddr = std::env::var("LITE_BIND_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:8130".to_owned())
        .parse()
        .context("LITE_BIND_ADDR must be a valid host:port")?;
    let data_dir =
        PathBuf::from(std::env::var("LITE_DATA_DIR").unwrap_or_else(|_| "/data".to_owned()));
    let chromium_path = PathBuf::from(
        std::env::var("CHROMIUM_PATH").unwrap_or_else(|_| "/usr/bin/chromium".to_owned()),
    );
    let max_sessions: u32 = std::env::var("LITE_MAX_SESSIONS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(4)
        .clamp(1, 64);
    let warm_pool_size: usize = std::env::var("LITE_WARM_POOL_SIZE")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0)
        .min(4);
    let default_idle_timeout_seconds: u64 = std::env::var("LITE_IDLE_TIMEOUT_SECONDS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(900)
        .clamp(30, 86_400);
    let allow_private_network = std::env::var("LITE_ALLOW_PRIVATE_NETWORK")
        .map(|v| v.eq_ignore_ascii_case("true"))
        .unwrap_or(false);
    let allowed_hosts = split_allowlist(
        std::env::var("LITE_ALLOWED_HOSTS")
            .unwrap_or_else(|_| "127.0.0.1:* localhost:*".to_owned()),
    );
    let allowed_origins = split_allowlist(
        std::env::var("LITE_ALLOWED_ORIGINS")
            .unwrap_or_else(|_| "http://127.0.0.1:* http://localhost:*".to_owned()),
    );

    let store = Arc::new(LiteStore::open(&data_dir.join("lite.sqlite3"))?);
    let explicit_token = std::env::var("LITE_BOOTSTRAP_TOKEN").ok();
    if let Some(issued) = store.ensure_bootstrap_token(explicit_token.as_deref(), max_sessions)? {
        let token_file = data_dir.join("bootstrap-token");
        write_private_file(&token_file, &format!("{}\n", issued.bearer_token))?;
        tracing::info!(
            token_id = issued.token.token_id,
            token_file = %token_file.display(),
            "Generated initial L0 Lite admin/agent bearer token (mode 0600)"
        );
    }

    let config = LiteConfig {
        data_dir,
        chromium_path,
        max_sessions,
        warm_pool_size,
        default_idle_timeout_seconds,
        allow_private_network,
        allowed_hosts,
        allowed_origins,
    };
    let runtime = Arc::new(LiteRuntime::new(config, store)?);
    let bg_runtime = runtime.clone();
    let maintenance = tokio::spawn(async move {
        let mut ticker = tokio::time::interval(Duration::from_secs(10));
        loop {
            ticker.tick().await;
            bg_runtime.maintenance_tick().await;
        }
    });

    let app = build_router(runtime.clone());
    let listener = tokio::net::TcpListener::bind(bind_addr)
        .await
        .with_context(|| format!("failed to bind {bind_addr}"))?;
    tracing::info!(
        %bind_addr,
        max_sessions,
        "Agent Browser Cloud L0 Lite (pure Rust) listening: MCP at /mcp, REST at /api/v1/lite/*, health at /healthz"
    );

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;

    maintenance.abort();
    runtime.shutdown().await;
    Ok(())
}

fn split_allowlist(raw: String) -> Vec<String> {
    raw.split(|c: char| c.is_whitespace() || c == ',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(ToOwned::to_owned)
        .collect()
}

fn write_private_file(path: &std::path::Path, content: &str) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, content)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
    }
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut sig) =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            sig.recv().await;
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
