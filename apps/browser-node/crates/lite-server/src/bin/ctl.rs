//! `browsercloud-ctl`: All-in-one pure-Rust CLI for Agent Browser Cloud (L0 / L1 / L2).
//!
//! Covers:
//! - Tiered Compose orchestration (`config | build | up | down | ps | logs`) with pure-Rust
//!   `--rust-mcp` and `--rust-workers` overlays
//! - `doctor`: checks Docker/OrbStack runtime, `chromium-seccomp.json`, host RAM, and L0 health
//! - `mcp-config`: generates ready-to-paste MCP configs for Claude Desktop, Cursor, Codex, and HTTP
//! - `token`: manages L0 Lite Agent tokens (`list | create | rotate | revoke`) over REST
//! - `bench`: runs `browsercloud-bench` against a live L0 Lite instance

use anyhow::{bail, ensure, Context, Result};
use reqwest::Client;
use serde_json::{json, Value};
use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;
use sysinfo::System;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Tier {
    Lite,
    Personal,
    Team,
}

#[derive(Debug, Clone)]
struct PlanOptions {
    tier: Tier,
    action: String,
    mcp: bool,
    rust_mcp: bool,
    web: bool,
    workers: bool,
    rust_workers: bool,
    vnc: bool,
    recording: bool,
    low_memory: bool,
    max_sessions: u32,
    node_mem_mb: u32,
    bind_host: String,
    extra: Vec<String>,
}

impl PlanOptions {
    fn for_tier(tier: Tier) -> Self {
        match tier {
            Tier::Lite => Self {
                tier,
                action: String::new(),
                mcp: true,
                rust_mcp: true,
                web: false,
                workers: false,
                rust_workers: false,
                vnc: false,
                recording: false,
                low_memory: true,
                max_sessions: 4,
                node_mem_mb: 1536,
                bind_host: "127.0.0.1".to_owned(),
                extra: Vec::new(),
            },
            Tier::Personal => Self {
                tier,
                action: String::new(),
                mcp: true,
                rust_mcp: true,
                web: true,
                workers: false,
                rust_workers: false,
                vnc: false,
                recording: false,
                low_memory: true,
                max_sessions: 2,
                node_mem_mb: 1536,
                bind_host: "127.0.0.1".to_owned(),
                extra: Vec::new(),
            },
            Tier::Team => Self {
                tier,
                action: String::new(),
                mcp: true,
                rust_mcp: true,
                web: true,
                workers: false,
                rust_workers: false,
                vnc: false,
                recording: false,
                low_memory: false,
                max_sessions: 6,
                node_mem_mb: 6144,
                bind_host: "127.0.0.1".to_owned(),
                extra: Vec::new(),
            },
        }
    }

    fn browser_node_target(&self) -> &'static str {
        if self.tier == Tier::Lite {
            "lite"
        } else if self.recording {
            "vision"
        } else if self.vnc {
            "desktop"
        } else {
            "core"
        }
    }

    fn build_compose_invocation(&self, root: &Path) -> (Vec<String>, Vec<(String, String)>) {
        let mut args = vec!["compose".to_owned()];
        let mut envs = vec![
            ("BIND_HOST".to_owned(), self.bind_host.clone()),
            (
                "BROWSER_LOW_MEMORY_MODE".to_owned(),
                self.low_memory.to_string(),
            ),
            (
                "BROWSER_NODE_TARGET".to_owned(),
                self.browser_node_target().to_owned(),
            ),
        ];

        match self.tier {
            Tier::Lite => {
                args.push("-f".to_owned());
                args.push(root.join("deploy/compose/lite.yml").display().to_string());
                envs.push((
                    "LITE_MAX_SESSIONS".to_owned(),
                    self.max_sessions.to_string(),
                ));
                envs.push((
                    "LITE_MEM_LIMIT".to_owned(),
                    format!("{}m", self.node_mem_mb),
                ));
            }
            Tier::Personal => {
                args.push("-f".to_owned());
                args.push(
                    root.join("deploy/compose/modular.yml")
                        .display()
                        .to_string(),
                );
                if self.low_memory {
                    args.push("-f".to_owned());
                    args.push(
                        root.join("deploy/compose/low-memory.yml")
                            .display()
                            .to_string(),
                    );
                }
                args.push("-f".to_owned());
                args.push(
                    root.join("deploy/compose/personal.yml")
                        .display()
                        .to_string(),
                );
            }
            Tier::Team => {
                args.push("-f".to_owned());
                args.push(
                    root.join("deploy/compose/modular.yml")
                        .display()
                        .to_string(),
                );
                if self.low_memory {
                    args.push("-f".to_owned());
                    args.push(
                        root.join("deploy/compose/low-memory.yml")
                            .display()
                            .to_string(),
                    );
                }
                args.push("-f".to_owned());
                args.push(root.join("deploy/compose/team.yml").display().to_string());
            }
        }

        if self.tier != Tier::Lite {
            if self.mcp && self.rust_mcp {
                args.push("-f".to_owned());
                args.push(
                    root.join("deploy/compose/mcp-gateway-rust.yml")
                        .display()
                        .to_string(),
                );
            }
            if self.rust_workers {
                args.push("-f".to_owned());
                args.push(
                    root.join("deploy/compose/worker-supervisor.yml")
                        .display()
                        .to_string(),
                );
            }
            if self.action == "down" || self.action == "ps" {
                for p in ["mcp", "web", "workers"] {
                    args.push("--profile".to_owned());
                    args.push(p.to_owned());
                }
            } else {
                if self.mcp {
                    args.push("--profile".to_owned());
                    args.push("mcp".to_owned());
                }
                if self.web {
                    args.push("--profile".to_owned());
                    args.push("web".to_owned());
                }
                if self.workers {
                    args.push("--profile".to_owned());
                    args.push("workers".to_owned());
                }
            }
            envs.push(("ENABLE_REMOTE_DESKTOP".to_owned(), self.vnc.to_string()));
            envs.push((
                "ENABLE_RECORDING_SCANNER".to_owned(),
                self.recording.to_string(),
            ));
            envs.push((
                "NODE_MAX_SESSIONS".to_owned(),
                self.max_sessions.to_string(),
            ));
            envs.push((
                "NODE_CERTIFIED_MEMORY_MIB".to_owned(),
                self.node_mem_mb.to_string(),
            ));
            envs.push((
                "BROWSER_NODE_MEM_LIMIT".to_owned(),
                format!("{}m", self.node_mem_mb),
            ));
            let any_workers = self.workers || self.rust_workers;
            envs.push((
                "AGENT_EXTERNAL_WORKER_ENABLED".to_owned(),
                any_workers.to_string(),
            ));
            envs.push((
                "AGENT_REVIEWER_EXTERNAL_ENABLED".to_owned(),
                any_workers.to_string(),
            ));
            envs.push((
                "AGENT_OUTCOME_VERIFIER_EXTERNAL_ENABLED".to_owned(),
                any_workers.to_string(),
            ));
            if self.vnc {
                envs.push(("BROWSER_XVFB_PATH".to_owned(), "/usr/bin/Xvfb".to_owned()));
                envs.push((
                    "BROWSER_X11VNC_PATH".to_owned(),
                    "/usr/bin/x11vnc".to_owned(),
                ));
            } else {
                envs.push(("BROWSER_XVFB_PATH".to_owned(), String::new()));
                envs.push(("BROWSER_X11VNC_PATH".to_owned(), String::new()));
            }
        }

        match self.action.as_str() {
            "up" => {
                args.push("up".to_owned());
                args.push("-d".to_owned());
                args.push("--build".to_owned());
            }
            other => args.push(other.to_owned()),
        }
        args.extend(self.extra.iter().cloned());
        (args, envs)
    }
}

pub fn render_mcp_client_config(
    client: &str,
    url: &str,
    token: &str,
    bridge_bin: &str,
) -> Result<Value> {
    let clean_url = url.trim_end_matches('/');
    let mcp_url = if clean_url.ends_with("/mcp") {
        clean_url.to_owned()
    } else {
        format!("{clean_url}/mcp")
    };
    match client.trim().to_ascii_lowercase().as_str() {
        "claude" | "cursor" | "stdio" => Ok(json!({
            "mcpServers": {
                "agent-browser-cloud": {
                    "command": bridge_bin,
                    "args": ["--url", mcp_url, "--token", token]
                }
            }
        })),
        "codex" | "http" | "streamable-http" => Ok(json!({
            "mcpServers": {
                "agent-browser-cloud": {
                    "type": "streamable-http",
                    "url": mcp_url,
                    "headers": {
                        "Authorization": format!("Bearer {token}")
                    }
                }
            }
        })),
        other => {
            bail!("unknown --client '{other}' (expected claude, cursor, codex, stdio, or http)")
        }
    }
}

async fn run_doctor(root: &Path, url: &str) -> Result<()> {
    let mut sys = System::new();
    sys.refresh_memory();
    let total_mib = sys.total_memory() / (1024 * 1024);
    let avail_mib = sys.available_memory() / (1024 * 1024);
    let recommended = if total_mib <= 4096 {
        "lite (L0 pure-Rust single container, ~2 MiB idle)"
    } else if total_mib <= 12288 {
        "personal (L1 Control Plane -Xmx384m + core headless node + Rust MCP)"
    } else {
        "team (L2 multi-node ready)"
    };
    let seccomp_ok = root.join("deploy/docker/chromium-seccomp.json").is_file();
    let docker_ctx = Command::new("docker")
        .args(["context", "show"])
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_owned())
        .unwrap_or_else(|| "unavailable".to_owned());

    let http = Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(2))
        .build()?;
    let health_status = match http
        .get(format!("{}/healthz", url.trim_end_matches('/')))
        .send()
        .await
    {
        Ok(r) if r.status().is_success() => "UP".to_owned(),
        Ok(r) => format!("HTTP_{}", r.status().as_u16()),
        Err(_) => "STOPPED".to_owned(),
    };

    let report = json!({
        "host": {
            "os": std::env::consts::OS,
            "arch": std::env::consts::ARCH,
            "totalMemoryMiB": total_mib,
            "availableMemoryMiB": avail_mib,
            "recommendedTier": recommended,
        },
        "docker": {
            "context": docker_ctx,
            "orbstackPreferredOnMac": std::env::consts::OS != "macos" || docker_ctx == "orbstack",
        },
        "security": {
            "chromiumSeccompProfilePresent": seccomp_ok,
        },
        "endpoint": {
            "url": url,
            "health": health_status,
        }
    });
    println!("{}", serde_json::to_string_pretty(&report)?);
    Ok(())
}

async fn run_token_cmd(args: &[String]) -> Result<()> {
    ensure!(
        !args.is_empty(),
        "Usage: browsercloud-ctl token <list|create|rotate|revoke> [--url URL] [--token ADMIN_TOKEN] ..."
    );
    let sub = args[0].as_str();
    let mut url = env::var("LITE_URL").unwrap_or_else(|_| "http://127.0.0.1:8130".to_owned());
    let mut admin_token = env::var("LITE_TOKEN").unwrap_or_default();
    let mut agent_id = String::new();
    let mut label = "agent-client".to_owned();
    let mut scopes = vec!["mcp".to_owned(), "sessions".to_owned()];
    let mut max_sessions: u32 = 2;
    let mut token_id = String::new();

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--url" => {
                url = args.get(i + 1).context("missing --url")?.clone();
                i += 2;
            }
            "--token" => {
                admin_token = args.get(i + 1).context("missing --token")?.clone();
                i += 2;
            }
            "--agent-id" => {
                agent_id = args.get(i + 1).context("missing --agent-id")?.clone();
                i += 2;
            }
            "--label" => {
                label = args.get(i + 1).context("missing --label")?.clone();
                i += 2;
            }
            "--scopes" => {
                scopes = args
                    .get(i + 1)
                    .context("missing --scopes")?
                    .split(',')
                    .map(|s| s.trim().to_owned())
                    .filter(|s| !s.is_empty())
                    .collect();
                i += 2;
            }
            "--max-sessions" => {
                max_sessions = args.get(i + 1).context("missing --max-sessions")?.parse()?;
                i += 2;
            }
            "--token-id" => {
                token_id = args.get(i + 1).context("missing --token-id")?.clone();
                i += 2;
            }
            other => bail!("unknown token flag: {other}"),
        }
    }
    ensure!(!admin_token.is_empty(), "--token or LITE_TOKEN required");
    let base = url.trim_end_matches('/');
    let http = Client::builder().no_proxy().build()?;
    let resp = match sub {
        "list" => {
            http.get(format!("{base}/api/v1/lite/tokens"))
                .header("authorization", format!("Bearer {admin_token}"))
                .send()
                .await?
        }
        "create" => {
            ensure!(!agent_id.is_empty(), "--agent-id required for token create");
            http.post(format!("{base}/api/v1/lite/tokens"))
                .header("authorization", format!("Bearer {admin_token}"))
                .json(&json!({
                    "agentId": agent_id,
                    "label": label,
                    "scopes": scopes,
                    "maxSessions": max_sessions,
                }))
                .send()
                .await?
        }
        "rotate" => {
            ensure!(!token_id.is_empty(), "--token-id required for token rotate");
            http.post(format!("{base}/api/v1/lite/tokens/{token_id}/rotate"))
                .header("authorization", format!("Bearer {admin_token}"))
                .send()
                .await?
        }
        "quota" => {
            ensure!(!token_id.is_empty(), "--token-id required for token quota");
            http.patch(format!("{base}/api/v1/lite/tokens/{token_id}"))
                .header("authorization", format!("Bearer {admin_token}"))
                .json(&json!({ "maxSessions": max_sessions }))
                .send()
                .await?
        }
        "revoke" => {
            ensure!(!token_id.is_empty(), "--token-id required for token revoke");
            http.delete(format!("{base}/api/v1/lite/tokens/{token_id}"))
                .header("authorization", format!("Bearer {admin_token}"))
                .send()
                .await?
        }
        other => bail!("unknown token action '{other}' (expected list|create|rotate|quota|revoke)"),
    };
    let status = resp.status();
    let text = resp.text().await?;
    println!("{text}");
    ensure!(status.is_success(), "token command failed: HTTP {status}");
    Ok(())
}

async fn run_profile_cmd(args: &[String]) -> Result<()> {
    let sub = args.first().map(String::as_str).unwrap_or("list");
    let mut url = env::var("LITE_URL").unwrap_or_else(|_| "http://127.0.0.1:8130".to_owned());
    let mut bearer = env::var("LITE_TOKEN").unwrap_or_default();
    let mut profile_id = String::new();
    let mut agent_id = String::new();
    let mut name = String::new();
    let mut allow_hosts: Vec<String> = Vec::new();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--url" => {
                url = args.get(i + 1).context("missing --url")?.clone();
                i += 2;
            }
            "--token" => {
                bearer = args.get(i + 1).context("missing --token")?.clone();
                i += 2;
            }
            "--profile-id" => {
                profile_id = args.get(i + 1).context("missing --profile-id")?.clone();
                i += 2;
            }
            "--agent-id" => {
                agent_id = args.get(i + 1).context("missing --agent-id")?.clone();
                i += 2;
            }
            "--name" => {
                name = args.get(i + 1).context("missing --name")?.clone();
                i += 2;
            }
            "--allow-private-host" => {
                allow_hosts.push(
                    args.get(i + 1)
                        .context("missing --allow-private-host")?
                        .clone(),
                );
                i += 2;
            }
            other => bail!("unknown profile flag: {other}"),
        }
    }
    ensure!(!bearer.is_empty(), "--token or LITE_TOKEN required");
    let base = url.trim_end_matches('/');
    let http = Client::builder().no_proxy().build()?;
    let resp = match sub {
        "list" => {
            http.get(format!("{base}/api/v1/lite/profiles"))
                .header("authorization", format!("Bearer {bearer}"))
                .send()
                .await?
        }
        "create" => {
            ensure!(!name.is_empty(), "--name required for profile create");
            http.post(format!("{base}/api/v1/lite/profiles"))
                .header("authorization", format!("Bearer {bearer}"))
                .json(&json!({
                    "name": name,
                    "allowed_private_hosts": allow_hosts,
                }))
                .send()
                .await?
        }
        "grant" => {
            ensure!(!profile_id.is_empty(), "--profile-id required");
            ensure!(!agent_id.is_empty(), "--agent-id required");
            http.post(format!("{base}/api/v1/lite/profiles/{profile_id}/grants"))
                .header("authorization", format!("Bearer {bearer}"))
                .json(&json!({ "agentId": agent_id }))
                .send()
                .await?
        }
        "revoke" => {
            ensure!(!profile_id.is_empty(), "--profile-id required");
            ensure!(!agent_id.is_empty(), "--agent-id required");
            http.delete(format!(
                "{base}/api/v1/lite/profiles/{profile_id}/grants/{agent_id}"
            ))
            .header("authorization", format!("Bearer {bearer}"))
            .send()
            .await?
        }
        "open" => {
            ensure!(!profile_id.is_empty(), "--profile-id required");
            http.post(format!("{base}/api/v1/lite/profiles/{profile_id}/open"))
                .header("authorization", format!("Bearer {bearer}"))
                .json(&json!({ "start": true }))
                .send()
                .await?
        }
        "allow-private" => {
            ensure!(!profile_id.is_empty(), "--profile-id required");
            http.patch(format!(
                "{base}/api/v1/lite/profiles/{profile_id}/network-policy"
            ))
            .header("authorization", format!("Bearer {bearer}"))
            .json(&json!({ "allowedPrivateHosts": allow_hosts }))
            .send()
            .await?
        }
        other => bail!(
            "unknown profile action '{other}' (expected list|create|grant|revoke|open|allow-private)"
        ),
    };
    let status = resp.status();
    let text = resp.text().await?;
    println!("{text}");
    ensure!(status.is_success(), "profile command failed: HTTP {status}");
    Ok(())
}

fn parse_compose_args<I: IntoIterator<Item = String>>(iter: I) -> Result<PlanOptions> {
    let raw: Vec<String> = iter.into_iter().collect();
    let mut tier = Tier::Lite;
    for window in raw.windows(2) {
        if window[0] == "--tier" {
            tier = match window[1].as_str() {
                "lite" | "l0" => Tier::Lite,
                "personal" | "l1" => Tier::Personal,
                "team" | "l2" => Tier::Team,
                other => bail!("unknown --tier '{other}' (expected lite, personal, or team)"),
            };
        }
    }
    let mut opts = PlanOptions::for_tier(tier);
    let mut i = 0;
    while i < raw.len() {
        match raw[i].as_str() {
            "--tier" => i += 2,
            "--mcp" => {
                opts.mcp = true;
                i += 1;
            }
            "--no-mcp" => {
                opts.mcp = false;
                i += 1;
            }
            "--rust-mcp" => {
                opts.rust_mcp = true;
                i += 1;
            }
            "--python-mcp" => {
                opts.rust_mcp = false;
                i += 1;
            }
            "--web" => {
                opts.web = true;
                i += 1;
            }
            "--no-web" => {
                opts.web = false;
                i += 1;
            }
            "--workers" => {
                opts.workers = true;
                i += 1;
            }
            "--no-workers" => {
                opts.workers = false;
                i += 1;
            }
            "--rust-workers" => {
                opts.rust_workers = true;
                i += 1;
            }
            "--vnc" => {
                opts.vnc = true;
                i += 1;
            }
            "--no-vnc" => {
                opts.vnc = false;
                i += 1;
            }
            "--recording" => {
                opts.recording = true;
                i += 1;
            }
            "--no-recording" => {
                opts.recording = false;
                i += 1;
            }
            "--low-memory" => {
                opts.low_memory = true;
                i += 1;
            }
            "--no-low-memory" => {
                opts.low_memory = false;
                i += 1;
            }
            "--max-sessions" => {
                let val = raw
                    .get(i + 1)
                    .ok_or_else(|| anyhow::anyhow!("missing value for --max-sessions"))?;
                opts.max_sessions = val.parse()?;
                ensure!(opts.max_sessions >= 1, "--max-sessions must be >= 1");
                i += 2;
            }
            "--node-mem-mb" => {
                let val = raw
                    .get(i + 1)
                    .ok_or_else(|| anyhow::anyhow!("missing value for --node-mem-mb"))?;
                opts.node_mem_mb = val.parse()?;
                ensure!(opts.node_mem_mb >= 512, "--node-mem-mb must be >= 512");
                i += 2;
            }
            "--bind-host" => {
                opts.bind_host = raw
                    .get(i + 1)
                    .ok_or_else(|| anyhow::anyhow!("missing value for --bind-host"))?
                    .clone();
                i += 2;
            }
            "--" => {
                opts.extra.extend(raw[i + 1..].iter().cloned());
                break;
            }
            "config" | "build" | "up" | "down" | "ps" | "logs" if opts.action.is_empty() => {
                opts.action = raw[i].clone();
                i += 1;
            }
            "-h" | "--help" => {
                print_usage();
                std::process::exit(0);
            }
            other => bail!("unknown argument '{other}'"),
        }
    }
    ensure!(
        !opts.action.is_empty(),
        "action required: config | build | up | down | ps | logs | doctor | mcp-config | token | bench"
    );
    Ok(opts)
}

fn print_usage() {
    eprintln!(
        "browsercloud-ctl — Pure-Rust all-in-one CLI for Agent Browser Cloud\n\
         \n\
         Stack Commands:\n\
           browsercloud-ctl [--tier lite|personal|team] [flags] <config|build|up|down|ps|logs>\n\
         \n\
         Utility Commands:\n\
           browsercloud-ctl doctor [--url http://127.0.0.1:8130]\n\
           browsercloud-ctl mcp-config --client claude|cursor|codex|http [--url URL] [--token TOKEN]\n\
           browsercloud-ctl token <list|create|rotate|revoke> [--url URL] [--token ADMIN_TOKEN]\n\
           browsercloud-ctl bench [browsercloud-bench args...]\n\
         \n\
         Toggles (L1/L2):\n\
           --mcp/--no-mcp  --rust-mcp/--python-mcp  --web/--no-web\n\
           --workers/--no-workers  --rust-workers  --vnc/--no-vnc\n\
           --recording/--no-recording  --low-memory/--no-low-memory\n\
           --max-sessions <N>  --node-mem-mb <MB>  --bind-host <IP>"
    );
}

#[tokio::main]
async fn main() -> Result<()> {
    let raw_args: Vec<String> = env::args().skip(1).collect();
    if raw_args.is_empty() || matches!(raw_args[0].as_str(), "-h" | "--help") {
        print_usage();
        return Ok(());
    }
    let root = env::var("BROWSERCLOUD_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|_| env::current_dir().unwrap_or_else(|_| PathBuf::from(".")));

    match raw_args[0].as_str() {
        "doctor" => {
            let mut url =
                env::var("LITE_URL").unwrap_or_else(|_| "http://127.0.0.1:8130".to_owned());
            if let Some(pos) = raw_args.iter().position(|a| a == "--url") {
                if let Some(v) = raw_args.get(pos + 1) {
                    url = v.clone();
                }
            }
            return run_doctor(&root, &url).await;
        }
        "mcp-config" => {
            let mut client = "claude".to_owned();
            let mut url =
                env::var("LITE_URL").unwrap_or_else(|_| "http://127.0.0.1:8130".to_owned());
            let mut token =
                env::var("LITE_TOKEN").unwrap_or_else(|_| "<YOUR_AGENT_TOKEN>".to_owned());
            let mut bridge_bin = "browsercloud-mcp-bridge".to_owned();
            let mut i = 1;
            while i < raw_args.len() {
                match raw_args[i].as_str() {
                    "--client" => {
                        client = raw_args.get(i + 1).context("missing --client")?.clone();
                        i += 2;
                    }
                    "--url" => {
                        url = raw_args.get(i + 1).context("missing --url")?.clone();
                        i += 2;
                    }
                    "--token" => {
                        token = raw_args.get(i + 1).context("missing --token")?.clone();
                        i += 2;
                    }
                    "--bridge-bin" => {
                        bridge_bin = raw_args.get(i + 1).context("missing --bridge-bin")?.clone();
                        i += 2;
                    }
                    other => bail!("unknown mcp-config flag: {other}"),
                }
            }
            let doc = render_mcp_client_config(&client, &url, &token, &bridge_bin)?;
            println!("{}", serde_json::to_string_pretty(&doc)?);
            return Ok(());
        }
        "token" => return run_token_cmd(&raw_args[1..]).await,
        "profile" => return run_profile_cmd(&raw_args[1..]).await,
        "bench" => {
            let status = Command::new("browsercloud-bench")
                .args(&raw_args[1..])
                .status()
                .context("failed to invoke browsercloud-bench")?;
            if !status.success() {
                std::process::exit(status.code().unwrap_or(1));
            }
            return Ok(());
        }
        _ => {}
    }

    let opts = parse_compose_args(raw_args)?;
    let (args, envs) = opts.build_compose_invocation(&root);
    eprintln!(
        "[browsercloud-ctl] tier={:?} target={} action={} mcp={} rust_mcp={} web={} workers={} rust_workers={} vnc={} recording={} low_memory={}",
        opts.tier,
        opts.browser_node_target(),
        opts.action,
        opts.mcp,
        opts.rust_mcp,
        opts.web,
        opts.workers,
        opts.rust_workers,
        opts.vnc,
        opts.recording,
        opts.low_memory
    );
    let status = Command::new("docker").args(&args).envs(envs).status()?;
    if !status.success() {
        std::process::exit(status.code().unwrap_or(1));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lite_tier_selects_single_rust_compose_and_lite_target() {
        let opts = parse_compose_args(vec![
            "--tier".to_owned(),
            "lite".to_owned(),
            "config".to_owned(),
        ])
        .unwrap();
        assert_eq!(opts.browser_node_target(), "lite");
        let (args, envs) = opts.build_compose_invocation(Path::new("/repo"));
        assert!(args.iter().any(|a| a.ends_with("deploy/compose/lite.yml")));
        assert!(!args.iter().any(|a| a.ends_with("modular.yml")));
        assert!(envs
            .iter()
            .any(|(k, v)| k == "LITE_MAX_SESSIONS" && v == "4"));
    }

    #[test]
    fn personal_tier_switches_browser_node_target_by_vnc_and_recording_flags() {
        let core = parse_compose_args(vec![
            "--tier".to_owned(),
            "personal".to_owned(),
            "build".to_owned(),
        ])
        .unwrap();
        assert_eq!(core.browser_node_target(), "core");
        let (args, _) = core.build_compose_invocation(Path::new("/repo"));
        assert!(args
            .iter()
            .any(|a| a.ends_with("deploy/compose/mcp-gateway-rust.yml")));

        let desktop = parse_compose_args(vec![
            "--tier".to_owned(),
            "personal".to_owned(),
            "--vnc".to_owned(),
            "build".to_owned(),
        ])
        .unwrap();
        assert_eq!(desktop.browser_node_target(), "desktop");

        let vision = parse_compose_args(vec![
            "--tier".to_owned(),
            "personal".to_owned(),
            "--recording".to_owned(),
            "--rust-workers".to_owned(),
            "build".to_owned(),
        ])
        .unwrap();
        assert_eq!(vision.browser_node_target(), "vision");
        let (vargs, _) = vision.build_compose_invocation(Path::new("/repo"));
        assert!(vargs
            .iter()
            .any(|a| a.ends_with("deploy/compose/worker-supervisor.yml")));
    }

    #[test]
    fn renders_claude_stdio_and_codex_http_mcp_configs() {
        let claude = render_mcp_client_config(
            "claude",
            "http://127.0.0.1:8130",
            "bcl_tok",
            "browsercloud-mcp-bridge",
        )
        .unwrap();
        assert_eq!(
            claude["mcpServers"]["agent-browser-cloud"]["command"],
            "browsercloud-mcp-bridge"
        );
        let codex = render_mcp_client_config(
            "codex",
            "http://127.0.0.1:8130",
            "bcl_tok",
            "browsercloud-mcp-bridge",
        )
        .unwrap();
        assert_eq!(
            codex["mcpServers"]["agent-browser-cloud"]["url"],
            "http://127.0.0.1:8130/mcp"
        );
        assert_eq!(
            codex["mcpServers"]["agent-browser-cloud"]["headers"]["Authorization"],
            "Bearer bcl_tok"
        );
    }
}
