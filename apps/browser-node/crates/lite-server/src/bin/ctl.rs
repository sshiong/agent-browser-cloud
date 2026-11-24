//! `browsercloud-ctl`: Pure-Rust tiered deployment & modular build controller (L0 / L1 / L2).
//!
//! Replaces heavy shell orchestration with a typed Rust CLI that composes `lite`, `personal`,
//! and `team` stacks, manages modular profiles (`mcp`, `web`, `workers`), and sets Browser Node
//! image targets (`lite`, `core`, `desktop`, `vision`) and low-memory runtime bounds.

use anyhow::{bail, ensure, Result};
use std::env;
use std::path::PathBuf;
use std::process::Command;

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
    web: bool,
    workers: bool,
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
                web: false,
                workers: false,
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
                web: true,
                workers: false,
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
                web: true,
                workers: false,
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

    fn build_compose_invocation(
        &self,
        root: &std::path::Path,
    ) -> (Vec<String>, Vec<(String, String)>) {
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
            envs.push((
                "AGENT_EXTERNAL_WORKER_ENABLED".to_owned(),
                self.workers.to_string(),
            ));
            envs.push((
                "AGENT_REVIEWER_EXTERNAL_ENABLED".to_owned(),
                self.workers.to_string(),
            ));
            envs.push((
                "AGENT_OUTCOME_VERIFIER_EXTERNAL_ENABLED".to_owned(),
                self.workers.to_string(),
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

fn parse_args<I: IntoIterator<Item = String>>(iter: I) -> Result<PlanOptions> {
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
            "config" | "build" | "up" | "down" | "ps" if opts.action.is_empty() => {
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
        "action required: config | build | up | down | ps"
    );
    Ok(opts)
}

fn print_usage() {
    eprintln!(
        "Usage: browsercloud-ctl [--tier lite|personal|team] [flags] <config|build|up|down|ps>\n\
         \n\
         Tiers:\n\
           --tier lite      L0 single pure-Rust container + SQLite + native MCP (no Java/PG/Redis/Python) [default]\n\
           --tier personal  L1 single-host Control Plane (-Xmx384m) + PG + Web + Core Browser Node\n\
           --tier team      L2 multi-node ready Control Plane + PG + Redis + Web + MCP + Browser Node\n\
         \n\
         Toggles (L1/L2):\n\
           --mcp/--no-mcp  --web/--no-web  --workers/--no-workers  --vnc/--no-vnc\n\
           --recording/--no-recording  --low-memory/--no-low-memory\n\
           --max-sessions <N>  --node-mem-mb <MB>  --bind-host <IP>"
    );
}

fn main() -> Result<()> {
    let opts = parse_args(env::args().skip(1))?;
    let root = env::var("BROWSERCLOUD_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|_| env::current_dir().unwrap_or_else(|_| PathBuf::from(".")));
    let (args, envs) = opts.build_compose_invocation(&root);
    eprintln!(
        "[browsercloud-ctl] tier={:?} target={} action={} mcp={} web={} workers={} vnc={} recording={} low_memory={}",
        opts.tier,
        opts.browser_node_target(),
        opts.action,
        opts.mcp,
        opts.web,
        opts.workers,
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
    use std::path::Path;

    #[test]
    fn lite_tier_selects_single_rust_compose_and_lite_target() {
        let opts = parse_args(vec![
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
        let core = parse_args(vec![
            "--tier".to_owned(),
            "personal".to_owned(),
            "build".to_owned(),
        ])
        .unwrap();
        assert_eq!(core.browser_node_target(), "core");

        let desktop = parse_args(vec![
            "--tier".to_owned(),
            "personal".to_owned(),
            "--vnc".to_owned(),
            "build".to_owned(),
        ])
        .unwrap();
        assert_eq!(desktop.browser_node_target(), "desktop");

        let vision = parse_args(vec![
            "--tier".to_owned(),
            "personal".to_owned(),
            "--recording".to_owned(),
            "build".to_owned(),
        ])
        .unwrap();
        assert_eq!(vision.browser_node_target(), "vision");
    }
}
