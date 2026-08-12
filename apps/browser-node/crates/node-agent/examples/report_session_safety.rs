use anyhow::{Context, Result};
use node_contracts::proto::{
    node_event_service_client::NodeEventServiceClient, ReportSessionResourcesRequest,
};
use std::time::{SystemTime, UNIX_EPOCH};
use tonic::transport::{Certificate, ClientTlsConfig, Endpoint, Identity};

fn required(name: &str) -> Result<String> {
    std::env::var(name).with_context(|| format!("{name} is required"))
}

#[tokio::main]
async fn main() -> Result<()> {
    rustls::crypto::ring::default_provider()
        .install_default()
        .map_err(|_| anyhow::anyhow!("failed to install the process rustls crypto provider"))?;
    let target = required("CONTROL_PLANE_EVENT_TARGET")?;
    let target = if target.starts_with("https://") {
        target
    } else {
        format!("https://{target}")
    };
    let tls = ClientTlsConfig::new()
        .ca_certificate(Certificate::from_pem(std::fs::read(required(
            "GRPC_TLS_CA_CERT",
        )?)?))
        .identity(Identity::from_pem(
            std::fs::read(required("GRPC_TLS_CERT")?)?,
            std::fs::read(required("GRPC_TLS_KEY")?)?,
        ))
        .domain_name(required("CONTROL_PLANE_TLS_SERVER_NAME")?);
    let channel = Endpoint::from_shared(target)?
        .connect_timeout(std::time::Duration::from_secs(2))
        .timeout(std::time::Duration::from_secs(2))
        .tls_config(tls)?
        .connect()
        .await?;
    let observed_at_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)?
        .as_millis()
        .try_into()
        .context("current timestamp exceeds i64")?;
    let session_id = required("SESSION_ID")?;
    let mut client = NodeEventServiceClient::new(channel);
    let response = client
        .report_session_resources(ReportSessionResourcesRequest {
            node_id: required("NODE_ID")?,
            tenant_id: required("TENANT_ID")?,
            session_id: session_id.clone(),
            context_epoch: required("CONTEXT_EPOCH")?.parse()?,
            observed_at_ms,
            cpu_percent: None,
            memory_rss_mib: None,
            memory_psi_some_avg10: None,
            renderer_count: None,
            tab_count: None,
            main_thread_blocked_ms: None,
            agent_action_latency_ms: None,
            state_diff_queue_depth: None,
            profile_io_bytes_per_second: None,
            extension_cpu_percent: None,
            extension_memory_mib: None,
            remote_desktop_frame_age_ms: None,
            media_encoder_percent: None,
            danger_event: String::new(),
            input_active: Some(false),
            active_drag: Some(false),
            pressed_key_count: Some(0),
            pressed_button_count: Some(0),
            active_upload_count: Some(0),
            active_download_count: Some(0),
            active_form_submission_count: Some(0),
            proxy_probe_succeeded: None,
            proxy_probe_latency_ms: None,
            proxy_observed_exit_ip: None,
            proxy_probe_error_code: String::new(),
            actual_resource_class: None,
            actual_cpu_millis: None,
            actual_memory_request_mib: None,
            actual_memory_limit_mib: None,
            actual_pid_limit: None,
            actual_tab_budget: None,
            actual_state_collector_budget_percent: None,
            actual_remote_desktop_bitrate_kbps: None,
            actual_extension_cpu_weight: None,
            actual_media_encoder_slots: None,
            actual_freeze_background_tabs: None,
            actual_block_new_tabs: None,
            actual_extension_background_policy: None,
            actual_success_trace_sample_percent: None,
            actual_observer_frame_rate_fps: None,
            actual_video_recording_enabled: None,
            actual_success_screenshot_sample_percent: None,
        })
        .await?
        .into_inner();
    anyhow::ensure!(
        response.accepted,
        "Control Plane rejected safety observation: {} {}",
        response.error_code,
        response.error_message
    );
    println!("session_id={session_id} accepted=true");
    Ok(())
}
