use anyhow::Result;
use browser_session_operator::{build_metrics_router, MetricsRegistry, Operator, OperatorConfig};
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

    let config = OperatorConfig::from_env()?;
    let metrics = Arc::new(MetricsRegistry::new());
    let bind = config.metrics_bind.clone();
    let app = build_metrics_router(metrics.clone());
    let listener = tokio::net::TcpListener::bind(&bind).await?;
    info!(%bind, "browsercloud-operator (pure Rust) metrics listening");
    tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });

    let operator = Operator::new(config, metrics)?;
    operator.run_forever().await
}
