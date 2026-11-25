use anyhow::{Context, Result};
use mcp_gateway::{build_router, GatewayConfig, GatewayState};
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::sync::Arc;
use std::time::Duration;
use tracing::info;
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
        let port = std::env::var("MCP_PORT")
            .ok()
            .and_then(|v| v.parse::<u16>().ok())
            .unwrap_or(8130);
        return run_healthcheck(port);
    }

    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .compact()
        .init();

    let cfg = GatewayConfig::from_env()?;
    let bind_addr: SocketAddr = format!("{}:{}", cfg.bind_host, cfg.bind_port).parse()?;
    let backend = format!("{:?}", cfg.backend);
    let upstream = cfg.upstream_url.clone();
    let state = Arc::new(GatewayState::new(cfg)?);
    let app = build_router(state);

    let listener = tokio::net::TcpListener::bind(bind_addr).await?;
    info!(%bind_addr, %backend, %upstream, "browsercloud-mcp-gateway (pure Rust) listening");
    axum::serve(listener, app).await?;
    Ok(())
}
