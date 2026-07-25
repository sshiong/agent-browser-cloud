//! Runtime Supervisor。
//!
//! 负责管理 Chromium Runtime 的生命周期。

use anyhow::Context;
use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::net::TcpStream;
use tokio::process::Child;
use tokio::sync::Mutex;
use tokio::time::{sleep, timeout, Duration, Instant};

/// Runtime 规格。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuntimeSpec {
    pub session_id: String,
    pub runtime_build_id: String,
    pub profile_dir: PathBuf,
    pub display: String,
    pub cdp_port: u16,
    pub vnc_port: Option<u16>,
}

/// Runtime 句柄。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuntimeHandle {
    pub session_id: String,
    pub pid: u32,
    pub process_started_at: u64,
    pub browser_generation: u64,
    pub cdp_endpoint: String,
    pub display: Option<String>,
    pub vnc_endpoint: Option<String>,
}

/// Runtime 健康状态。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum RuntimeHealth {
    Healthy,
    Degraded(String),
    Crashed(String),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuntimeMetrics {
    pub pid: u32,
    pub resident_memory_bytes: u64,
    pub virtual_memory_bytes: u64,
    pub cpu_usage_percent: f32,
}

/// Runtime Supervisor trait。
#[async_trait]
pub trait RuntimeSupervisor: Send + Sync {
    async fn start(&self, spec: RuntimeSpec) -> anyhow::Result<RuntimeHandle>;
    async fn stop(&self, session_id: &str) -> anyhow::Result<()>;
    async fn health(&self, session_id: &str) -> anyhow::Result<RuntimeHealth>;
    async fn metrics(&self, session_id: &str) -> anyhow::Result<RuntimeMetrics>;
}

struct RunningRuntime {
    handle: RuntimeHandle,
    child: Child,
    desktop: Option<DesktopProcesses>,
}

struct DesktopProcesses {
    xvfb: Child,
    vnc: Child,
}

#[derive(Debug, Clone)]
pub struct DesktopRuntimeConfig {
    pub xvfb_binary: PathBuf,
    pub x11vnc_binary: PathBuf,
    pub width: u16,
    pub height: u16,
    pub depth: u8,
}

/// Chromium Runtime Supervisor 实现。
pub struct ChromiumRuntimeSupervisor {
    chromium_binary: PathBuf,
    runtimes: Arc<Mutex<HashMap<String, RunningRuntime>>>,
    generations: Arc<Mutex<HashMap<String, u64>>>,
    desktop: Option<DesktopRuntimeConfig>,
}

impl ChromiumRuntimeSupervisor {
    pub fn new(chromium_binary: PathBuf) -> Self {
        Self {
            chromium_binary,
            runtimes: Arc::new(Mutex::new(HashMap::new())),
            generations: Arc::new(Mutex::new(HashMap::new())),
            desktop: None,
        }
    }

    pub fn with_desktop(mut self, desktop: DesktopRuntimeConfig) -> anyhow::Result<Self> {
        anyhow::ensure!(
            desktop.width >= 320 && desktop.height >= 240,
            "desktop dimensions are too small"
        );
        anyhow::ensure!(
            matches!(desktop.depth, 16 | 24 | 32),
            "desktop depth must be 16, 24, or 32"
        );
        self.desktop = Some(desktop);
        Ok(self)
    }

    fn validate_spec(spec: &RuntimeSpec) -> anyhow::Result<()> {
        let valid_session_id = spec.session_id.starts_with("ses_")
            && spec
                .session_id
                .chars()
                .all(|character| character.is_ascii_alphanumeric() || character == '_');
        anyhow::ensure!(valid_session_id, "invalid session id");
        anyhow::ensure!(spec.cdp_port > 0, "cdp port must be assigned");
        if spec.display.is_empty() {
            anyhow::ensure!(
                spec.vnc_port.is_none(),
                "headless runtime cannot expose a VNC port"
            );
        } else {
            anyhow::ensure!(
                spec.vnc_port.is_some(),
                "desktop runtime requires a VNC port"
            );
            anyhow::ensure!(
                spec.display.starts_with(':')
                    && spec.display[1..]
                        .chars()
                        .all(|character| character.is_ascii_digit()),
                "display must use the :N format"
            );
        }
        Ok(())
    }

    async fn wait_for_tcp(endpoint: SocketAddr, deadline: Duration) -> anyhow::Result<()> {
        let expires_at = Instant::now() + deadline;
        loop {
            if TcpStream::connect(endpoint).await.is_ok() {
                return Ok(());
            }
            anyhow::ensure!(
                Instant::now() < expires_at,
                "desktop TCP readiness timed out"
            );
            sleep(Duration::from_millis(100)).await;
        }
    }

    async fn start_desktop(&self, spec: &RuntimeSpec) -> anyhow::Result<Option<DesktopProcesses>> {
        if spec.display.is_empty() {
            return Ok(None);
        }
        let config = self
            .desktop
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("desktop runtime is not configured"))?;
        let vnc_port = spec
            .vnc_port
            .ok_or_else(|| anyhow::anyhow!("VNC port is not assigned"))?;
        let mut xvfb = tokio::process::Command::new(&config.xvfb_binary)
            .arg(&spec.display)
            .arg("-screen")
            .arg("0")
            .arg(format!(
                "{}x{}x{}",
                config.width, config.height, config.depth
            ))
            .arg("-nolisten")
            .arg("tcp")
            .arg("-noreset")
            .arg("-ac")
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .kill_on_drop(true)
            .spawn()
            .context("failed to start Xvfb")?;
        sleep(Duration::from_millis(200)).await;
        if let Some(status) = xvfb.try_wait()? {
            anyhow::bail!("Xvfb exited during startup with {status}");
        }

        let mut vnc = match tokio::process::Command::new(&config.x11vnc_binary)
            .arg("-display")
            .arg(&spec.display)
            .arg("-localhost")
            .arg("-rfbport")
            .arg(vnc_port.to_string())
            .arg("-forever")
            .arg("-nevershared")
            .arg("-dontdisconnect")
            .arg("-nopw")
            .arg("-clear_keys")
            .arg("-clear_mods")
            .arg("-noxdamage")
            .arg("-quiet")
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .kill_on_drop(true)
            .spawn()
        {
            Ok(child) => child,
            Err(error) => {
                let _ = xvfb.start_kill();
                return Err(error).context("failed to start x11vnc");
            }
        };
        let endpoint = SocketAddr::from(([127, 0, 0, 1], vnc_port));
        if let Err(error) = Self::wait_for_tcp(endpoint, Duration::from_secs(10)).await {
            let _ = vnc.start_kill();
            let _ = xvfb.start_kill();
            return Err(error.context("x11vnc did not become ready"));
        }
        Ok(Some(DesktopProcesses { xvfb, vnc }))
    }

    async fn stop_child(child: &mut Child, deadline: Duration) {
        if child.try_wait().ok().flatten().is_some() {
            return;
        }
        let _ = child.start_kill();
        let _ = timeout(deadline, child.wait()).await;
    }

    async fn wait_for_cdp(endpoint: &str, deadline: Duration) -> anyhow::Result<()> {
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_millis(500))
            .timeout(Duration::from_secs(1))
            .no_proxy()
            .build()?;
        let version_endpoint = format!("{endpoint}/json/version");
        let expires_at = Instant::now() + deadline;
        loop {
            if let Ok(response) = client.get(&version_endpoint).send().await {
                if response.status().is_success() {
                    let payload: serde_json::Value = response.json().await?;
                    let browser = payload
                        .get("Browser")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or_default();
                    let websocket = payload
                        .get("webSocketDebuggerUrl")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or_default();
                    anyhow::ensure!(
                        !browser.is_empty() && websocket.starts_with("ws://"),
                        "CDP version response is missing Browser or webSocketDebuggerUrl"
                    );
                    return Ok(());
                }
            }
            anyhow::ensure!(Instant::now() < expires_at, "CDP readiness timed out");
            sleep(Duration::from_millis(100)).await;
        }
    }

    async fn cdp_is_ready(endpoint: &str) -> bool {
        Self::wait_for_cdp(endpoint, Duration::from_millis(800))
            .await
            .is_ok()
    }

    /// Node 重启后从本地 Journal 恢复 generation 下界，避免复用旧世代。
    pub async fn ensure_generation_at_least(&self, session_id: &str, generation: u64) {
        let mut generations = self.generations.lock().await;
        let current = generations.entry(session_id.to_owned()).or_default();
        *current = (*current).max(generation);
    }

    /// 清理 Node 异常退出后遗留的、且进程启动时间与 Lease 完全匹配的 Runtime。
    pub async fn terminate_orphan(
        &self,
        pid: u32,
        expected_started_at: u64,
    ) -> anyhow::Result<bool> {
        anyhow::ensure!(
            expected_started_at > 0,
            "runtime lease has no process start identity"
        );
        tokio::task::spawn_blocking(move || {
            let process_id = sysinfo::Pid::from_u32(pid);
            let mut system = sysinfo::System::new();
            system.refresh_processes(sysinfo::ProcessesToUpdate::Some(&[process_id]), true);
            let Some(process) = system.process(process_id) else {
                return Ok(false);
            };
            anyhow::ensure!(
                process.start_time() == expected_started_at,
                "runtime pid was reused by another process"
            );
            process
                .kill_with(sysinfo::Signal::Kill)
                .ok_or_else(|| anyhow::anyhow!("kill signal is unsupported"))
        })
        .await?
    }

    /// 远程桌面连接断开时，同时清理 X11 与 CDP 之外可能残留的按键状态。
    pub async fn release_desktop_input(&self, session_id: &str) -> anyhow::Result<()> {
        let display = self
            .runtimes
            .lock()
            .await
            .get(session_id)
            .and_then(|runtime| runtime.handle.display.clone());
        let Some(display) = display else {
            return Ok(());
        };
        let config = self
            .desktop
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("desktop runtime is not configured"))?;
        let status = tokio::process::Command::new(&config.x11vnc_binary)
            .arg("-display")
            .arg(display)
            .arg("-remote")
            .arg("clear_all")
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .await?;
        anyhow::ensure!(status.success(), "x11vnc clear_all command failed");
        Ok(())
    }

    pub async fn stop_all(&self) {
        let session_ids = self
            .runtimes
            .lock()
            .await
            .keys()
            .cloned()
            .collect::<Vec<_>>();
        for session_id in session_ids {
            if let Err(error) = self.stop(&session_id).await {
                tracing::warn!(session_id, error = %error, "Runtime shutdown cleanup failed");
            }
        }
    }
}

#[async_trait]
impl RuntimeSupervisor for ChromiumRuntimeSupervisor {
    async fn start(&self, spec: RuntimeSpec) -> anyhow::Result<RuntimeHandle> {
        Self::validate_spec(&spec)?;

        let mut runtimes = self.runtimes.lock().await;
        if runtimes.contains_key(&spec.session_id) {
            anyhow::bail!("runtime already exists for session {}", spec.session_id);
        }

        tokio::fs::create_dir_all(&spec.profile_dir).await?;
        let mut desktop = self.start_desktop(&spec).await?;

        let mut command = tokio::process::Command::new(&self.chromium_binary);
        command
            .arg(format!("--user-data-dir={}", spec.profile_dir.display()))
            .arg(format!("--remote-debugging-port={}", spec.cdp_port))
            .arg("--remote-debugging-address=127.0.0.1")
            .arg("--no-first-run")
            .arg("--disable-background-networking")
            .arg("--disable-sync")
            .arg("--disable-translate")
            .arg("--no-default-browser-check")
            .arg("--disable-default-apps")
            .arg("--disable-extensions")
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .kill_on_drop(true);

        if spec.display.is_empty() {
            command.arg("--headless=new");
        } else {
            command.env("DISPLAY", &spec.display);
        }

        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(error) => {
                if let Some(desktop) = desktop.as_mut() {
                    Self::stop_child(&mut desktop.vnc, Duration::from_secs(2)).await;
                    Self::stop_child(&mut desktop.xvfb, Duration::from_secs(2)).await;
                }
                return Err(error.into());
            }
        };
        let pid = child
            .id()
            .ok_or_else(|| anyhow::anyhow!("missing Chromium pid"))?;
        let browser_generation = {
            let mut generations = self.generations.lock().await;
            let generation = generations.entry(spec.session_id.clone()).or_default();
            *generation += 1;
            *generation
        };

        let handle = RuntimeHandle {
            session_id: spec.session_id.clone(),
            pid,
            process_started_at: {
                let process_id = sysinfo::Pid::from_u32(pid);
                let mut system = sysinfo::System::new();
                system.refresh_processes(sysinfo::ProcessesToUpdate::Some(&[process_id]), true);
                system
                    .process(process_id)
                    .map(sysinfo::Process::start_time)
                    .unwrap_or_default()
            },
            browser_generation,
            cdp_endpoint: format!("http://127.0.0.1:{}", spec.cdp_port),
            display: (!spec.display.is_empty()).then_some(spec.display.clone()),
            vnc_endpoint: spec.vnc_port.map(|port| format!("127.0.0.1:{port}")),
        };
        if let Err(error) = Self::wait_for_cdp(&handle.cdp_endpoint, Duration::from_secs(15)).await
        {
            let _ = child.start_kill();
            let _ = timeout(Duration::from_secs(2), child.wait()).await;
            if let Some(desktop) = desktop.as_mut() {
                Self::stop_child(&mut desktop.vnc, Duration::from_secs(2)).await;
                Self::stop_child(&mut desktop.xvfb, Duration::from_secs(2)).await;
            }
            return Err(error.context("Chromium started but CDP did not become ready"));
        }
        runtimes.insert(
            spec.session_id.clone(),
            RunningRuntime {
                handle: handle.clone(),
                child,
                desktop,
            },
        );

        tracing::info!(
            session_id = %spec.session_id,
            pid,
            browser_generation,
            "Chromium runtime started"
        );
        Ok(handle)
    }

    async fn stop(&self, session_id: &str) -> anyhow::Result<()> {
        let runtime = self.runtimes.lock().await.remove(session_id);
        let Some(mut runtime) = runtime else {
            return Ok(());
        };

        runtime.child.start_kill()?;
        if timeout(Duration::from_secs(10), runtime.child.wait())
            .await
            .is_err()
        {
            tracing::warn!(
                session_id,
                pid = runtime.handle.pid,
                "Timed out waiting for Chromium to exit"
            );
        }
        if let Some(mut desktop) = runtime.desktop {
            Self::stop_child(&mut desktop.vnc, Duration::from_secs(2)).await;
            Self::stop_child(&mut desktop.xvfb, Duration::from_secs(2)).await;
        }

        tracing::info!(
            session_id,
            pid = runtime.handle.pid,
            "Chromium runtime stopped"
        );
        Ok(())
    }

    async fn health(&self, session_id: &str) -> anyhow::Result<RuntimeHealth> {
        let (cdp_endpoint, desktop_degraded) = {
            let mut runtimes = self.runtimes.lock().await;
            let Some(runtime) = runtimes.get_mut(session_id) else {
                return Ok(RuntimeHealth::Crashed("runtime not found".into()));
            };

            match runtime.child.try_wait()? {
                None => {
                    let desktop_degraded = if let Some(desktop) = runtime.desktop.as_mut() {
                        if desktop.xvfb.try_wait()?.is_some() {
                            Some("Xvfb exited while Chromium is running".to_owned())
                        } else if desktop.vnc.try_wait()?.is_some() {
                            Some("x11vnc exited while Chromium is running".to_owned())
                        } else {
                            None
                        }
                    } else {
                        None
                    };
                    (runtime.handle.cdp_endpoint.clone(), desktop_degraded)
                }
                Some(status) => {
                    let reason = format!("Chromium exited with {status}");
                    runtimes.remove(session_id);
                    return Ok(RuntimeHealth::Crashed(reason));
                }
            }
        };

        if let Some(reason) = desktop_degraded {
            Ok(RuntimeHealth::Degraded(reason))
        } else if Self::cdp_is_ready(&cdp_endpoint).await {
            Ok(RuntimeHealth::Healthy)
        } else {
            Ok(RuntimeHealth::Degraded(
                "Chromium process is alive but CDP is unavailable".into(),
            ))
        }
    }

    async fn metrics(&self, session_id: &str) -> anyhow::Result<RuntimeMetrics> {
        let pid = self
            .runtimes
            .lock()
            .await
            .get(session_id)
            .map(|runtime| runtime.handle.pid)
            .ok_or_else(|| anyhow::anyhow!("runtime not found"))?;
        tokio::task::spawn_blocking(move || {
            let process_id = sysinfo::Pid::from_u32(pid);
            let mut system = sysinfo::System::new();
            system.refresh_processes(sysinfo::ProcessesToUpdate::Some(&[process_id]), true);
            let process = system
                .process(process_id)
                .ok_or_else(|| anyhow::anyhow!("runtime process is not visible"))?;
            Ok(RuntimeMetrics {
                pid,
                resident_memory_bytes: process.memory(),
                virtual_memory_bytes: process.virtual_memory(),
                cpu_usage_percent: process.cpu_usage(),
            })
        })
        .await?
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[tokio::test]
    async fn reports_missing_runtime_as_crashed() {
        let supervisor = ChromiumRuntimeSupervisor::new(PathBuf::from("/missing/chromium"));
        assert_eq!(
            supervisor.health("ses_missing").await.unwrap(),
            RuntimeHealth::Crashed("runtime not found".into())
        );
    }

    #[tokio::test]
    async fn rejects_invalid_session_id_before_spawning() {
        let supervisor = ChromiumRuntimeSupervisor::new(PathBuf::from("/missing/chromium"));
        let result = supervisor
            .start(RuntimeSpec {
                session_id: "../escape".into(),
                runtime_build_id: "runtime-1".into(),
                profile_dir: PathBuf::from("/tmp/should-not-be-created"),
                display: String::new(),
                cdp_port: 9222,
                vnc_port: None,
            })
            .await;
        assert!(result.is_err());
    }

    #[tokio::test]
    #[ignore = "requires REAL_CHROMIUM_PATH and launches a local browser"]
    async fn starts_probes_and_stops_real_chromium() {
        let chromium =
            std::env::var("REAL_CHROMIUM_PATH").expect("REAL_CHROMIUM_PATH must point to Chromium");
        let reservation = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let cdp_port = reservation.local_addr().unwrap().port();
        drop(reservation);
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let profile_dir =
            std::env::temp_dir().join(format!("browsercloud-runtime-supervisor-{nonce}"));
        let supervisor = ChromiumRuntimeSupervisor::new(PathBuf::from(chromium));
        let handle = supervisor
            .start(RuntimeSpec {
                session_id: "ses_real_runtime".into(),
                runtime_build_id: "runtime-real-test".into(),
                profile_dir: profile_dir.clone(),
                display: String::new(),
                cdp_port,
                vnc_port: None,
            })
            .await
            .unwrap();
        assert!(handle.pid > 0);
        assert!(handle.process_started_at > 0);
        assert!(handle.cdp_endpoint.ends_with(&cdp_port.to_string()));
        assert_eq!(
            supervisor.health("ses_real_runtime").await.unwrap(),
            RuntimeHealth::Healthy
        );
        let metrics = supervisor.metrics("ses_real_runtime").await.unwrap();
        assert_eq!(metrics.pid, handle.pid);
        assert!(metrics.resident_memory_bytes > 0);
        supervisor.stop("ses_real_runtime").await.unwrap();
        assert!(matches!(
            supervisor.health("ses_real_runtime").await.unwrap(),
            RuntimeHealth::Crashed(_)
        ));
        let _ = tokio::fs::remove_dir_all(profile_dir).await;
    }
}
