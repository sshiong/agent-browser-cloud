use anyhow::Result;
use mcp_gateway::{build_router, GatewayConfig, GatewayState};
use std::net::SocketAddr;
use std::sync::Arc;
use tracing::info;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<()> {
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
