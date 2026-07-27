//! Runtime Supervisor。
//!
//! 负责管理 Chromium Runtime 的生命周期。

use anyhow::Context;
use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::io::ErrorKind;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
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
    pub cache_dir: PathBuf,
    pub proxy_server: Option<String>,
    pub display: String,
    pub cdp_port: u16,
    pub vnc_port: Option<u16>,
    pub resource_limits: RuntimeResourceLimits,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RuntimeResourceLimits {
    pub resource_class: String,
    pub cpu_millis: u32,
    pub memory_request_mib: u32,
    pub memory_limit_mib: u32,
    pub pid_limit: u32,
    pub tab_budget: u32,
    pub desktop_required: bool,
    pub gpu_required: bool,
    pub native_os_required: bool,
    pub isolation_required: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeResourceAdjustment {
    pub previous: RuntimeResourceLimits,
    pub applied: RuntimeResourceLimits,
}

impl RuntimeResourceLimits {
    pub fn local_test_default() -> Self {
        Self {
            resource_class: "L2".to_owned(),
            cpu_millis: 600,
            memory_request_mib: 768,
            memory_limit_mib: 1280,
            pid_limit: 192,
            tab_budget: 8,
            desktop_required: false,
            gpu_required: false,
            native_os_required: false,
            isolation_required: false,
        }
    }
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
    pub cumulative_cpu_usage_micros: Option<u64>,
    pub cpu_limit_millis: u32,
    pub memory_psi_some_avg10: Option<f64>,
    pub process_count: Option<u32>,
    pub cumulative_browser_io_bytes: Option<u64>,
}

/// Runtime Supervisor trait。
#[async_trait]
pub trait RuntimeSupervisor: Send + Sync {
    async fn start(&self, spec: RuntimeSpec) -> anyhow::Result<RuntimeHandle>;
    async fn adjust_resources(
        &self,
        session_id: &str,
        limits: RuntimeResourceLimits,
    ) -> anyhow::Result<RuntimeResourceAdjustment>;
    async fn stop(&self, session_id: &str) -> anyhow::Result<()>;
    async fn health(&self, session_id: &str) -> anyhow::Result<RuntimeHealth>;
    async fn metrics(&self, session_id: &str) -> anyhow::Result<RuntimeMetrics>;
}

struct RunningRuntime {
    handle: RuntimeHandle,
    child: Child,
    desktop: Option<DesktopProcesses>,
    cgroup: Option<RuntimeCgroup>,
    resource_limits: RuntimeResourceLimits,
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

/// A cgroup v2 subtree that has been delegated to the unprivileged Node Agent.
///
/// The Node Agent never receives host-level cgroup privileges. Node provisioning must create
/// and chown this exact subtree to the Node Agent UID before the workload starts.
#[derive(Debug, Clone)]
pub struct CgroupV2Config {
    pub root: PathBuf,
}

#[derive(Debug, Clone)]
struct RuntimeCgroup {
    path: PathBuf,
    browser_path: Option<PathBuf>,
    desktop_path: Option<PathBuf>,
}

impl RuntimeCgroup {
    const CPU_PERIOD_MICROS: u64 = 100_000;

    fn prepare(config: &CgroupV2Config, spec: &RuntimeSpec) -> anyhow::Result<Self> {
        anyhow::ensure!(
            config.root.is_absolute(),
            "Runtime cgroup root must be absolute"
        );
        anyhow::ensure!(
            config.root.join("cgroup.controllers").is_file(),
            "Runtime cgroup root is not a delegated cgroup v2 subtree"
        );
        let controllers = fs::read_to_string(config.root.join("cgroup.controllers"))
            .context("failed to read delegated cgroup controllers")?;
        for required in ["cpu", "memory", "pids"] {
            anyhow::ensure!(
                controllers
                    .split_whitespace()
                    .any(|value| value == required),
                "delegated cgroup does not expose the {required} controller"
            );
        }
        let io_available = controllers.split_whitespace().any(|value| value == "io");
        let subtree_control = config.root.join("cgroup.subtree_control");
        fs::write(
            &subtree_control,
            if io_available {
                "+cpu +memory +pids +io"
            } else {
                "+cpu +memory +pids"
            },
        )
        .context("failed to enable delegated cgroup controllers")?;

        let path = config.root.join(&spec.session_id);
        fs::create_dir(&path).context("failed to create runtime cgroup")?;
        let result = (|| {
            let limits = &spec.resource_limits;
            let quota = u64::from(limits.cpu_millis)
                .saturating_mul(Self::CPU_PERIOD_MICROS)
                .div_ceil(1000);
            Self::write(
                &path,
                "cpu.max",
                format!("{quota} {}", Self::CPU_PERIOD_MICROS),
            )?;
            Self::write(
                &path,
                "memory.high",
                u64::from(limits.memory_request_mib)
                    .saturating_mul(1024 * 1024)
                    .to_string(),
            )?;
            Self::write(
                &path,
                "memory.max",
                u64::from(limits.memory_limit_mib)
                    .saturating_mul(1024 * 1024)
                    .to_string(),
            )?;
            let swap_max = path.join("memory.swap.max");
            if swap_max.exists() {
                fs::write(&swap_max, "0").context("failed to disable runtime swap")?;
            }
            Self::write(&path, "pids.max", limits.pid_limit.to_string())?;
            if io_available {
                Self::write(&path, "cgroup.subtree_control", "+io".to_owned())
                    .context("failed to enable Browser I/O accounting subtree")?;
                fs::create_dir(path.join("browser"))
                    .context("failed to create Browser I/O accounting cgroup")?;
                fs::create_dir(path.join("desktop"))
                    .context("failed to create desktop process cgroup")?;
            }
            Ok::<(), anyhow::Error>(())
        })();
        if let Err(error) = result {
            let _ = fs::remove_dir(path.join("browser"));
            let _ = fs::remove_dir(path.join("desktop"));
            let _ = fs::remove_dir(&path);
            return Err(error);
        }
        Ok(Self {
            browser_path: io_available.then(|| path.join("browser")),
            desktop_path: io_available.then(|| path.join("desktop")),
            path,
        })
    }

    fn write(path: &Path, setting: &str, value: String) -> anyhow::Result<()> {
        fs::write(path.join(setting), value)
            .with_context(|| format!("failed to enforce cgroup setting {setting}"))
    }

    fn attach_to(path: &Path, pid: u32) -> anyhow::Result<()> {
        anyhow::ensure!(pid > 0, "cannot attach an invalid process to cgroup");
        Self::write(path, "cgroup.procs", pid.to_string())
            .context("failed to attach runtime process to cgroup")
    }

    fn attach_browser(&self, pid: u32) -> anyhow::Result<()> {
        Self::attach_to(self.browser_path.as_deref().unwrap_or(&self.path), pid)
    }

    fn attach_desktop(&self, pid: u32) -> anyhow::Result<()> {
        Self::attach_to(self.desktop_path.as_deref().unwrap_or(&self.path), pid)
    }

    fn memory_current_bytes(&self) -> Option<u64> {
        fs::read_to_string(self.path.join("memory.current"))
            .ok()?
            .trim()
            .parse()
            .ok()
    }

    fn cumulative_cpu_usage_micros(&self) -> Option<u64> {
        fs::read_to_string(self.path.join("cpu.stat"))
            .ok()?
            .lines()
            .find_map(|line| {
                line.strip_prefix("usage_usec ")
                    .and_then(|value| value.trim().parse().ok())
            })
    }

    fn memory_psi_some_avg10(&self) -> Option<f64> {
        fs::read_to_string(self.path.join("memory.pressure"))
            .ok()?
            .lines()
            .find(|line| line.starts_with("some "))?
            .split_whitespace()
            .find_map(|field| field.strip_prefix("avg10="))?
            .parse()
            .ok()
    }

    fn process_count(&self) -> Option<u32> {
        fs::read_to_string(self.path.join("pids.current"))
            .ok()?
            .trim()
            .parse()
            .ok()
    }

    fn cumulative_browser_io_bytes(&self) -> Option<u64> {
        let contents = fs::read_to_string(self.browser_path.as_ref()?.join("io.stat")).ok()?;
        let mut total = 0_u64;
        let mut saw_counter = false;
        for field in contents.split_whitespace() {
            let Some(value) = field
                .strip_prefix("rbytes=")
                .or_else(|| field.strip_prefix("wbytes="))
            else {
                continue;
            };
            let value = value.parse::<u64>().ok()?;
            total = total.saturating_add(value);
            saw_counter = true;
        }
        (saw_counter || contents.trim().is_empty()).then_some(total)
    }

    fn adjust(
        &self,
        previous: &RuntimeResourceLimits,
        next: &RuntimeResourceLimits,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(
            next.cpu_millis > 0
                && next.memory_request_mib > 0
                && next.memory_limit_mib >= next.memory_request_mib
                && next.pid_limit >= 32
                && next.tab_budget > 0,
            "runtime resource adjustment is invalid"
        );
        if next.memory_limit_mib < previous.memory_limit_mib {
            let current_mib = self
                .memory_current_bytes()
                .unwrap_or_default()
                .div_ceil(1024 * 1024);
            anyhow::ensure!(
                current_mib <= u64::from(next.memory_request_mib),
                "runtime memory usage exceeds requested scale-down target"
            );
        }
        if next.pid_limit < previous.pid_limit {
            let current = self.process_count().unwrap_or_default();
            anyhow::ensure!(
                current <= next.pid_limit,
                "runtime process count exceeds requested PID scale-down target"
            );
        }

        let apply = || -> anyhow::Result<()> {
            let cpu_quota = u64::from(next.cpu_millis)
                .saturating_mul(Self::CPU_PERIOD_MICROS)
                .div_ceil(1000);
            Self::write(
                &self.path,
                "cpu.max",
                format!("{cpu_quota} {}", Self::CPU_PERIOD_MICROS),
            )?;
            if next.memory_limit_mib >= previous.memory_limit_mib {
                Self::write(
                    &self.path,
                    "memory.max",
                    u64::from(next.memory_limit_mib)
                        .saturating_mul(1024 * 1024)
                        .to_string(),
                )?;
                Self::write(
                    &self.path,
                    "memory.high",
                    u64::from(next.memory_request_mib)
                        .saturating_mul(1024 * 1024)
                        .to_string(),
                )?;
            } else {
                Self::write(
                    &self.path,
                    "memory.high",
                    u64::from(next.memory_request_mib)
                        .saturating_mul(1024 * 1024)
                        .to_string(),
                )?;
                Self::write(
                    &self.path,
                    "memory.max",
                    u64::from(next.memory_limit_mib)
                        .saturating_mul(1024 * 1024)
                        .to_string(),
                )?;
            }
            Self::write(&self.path, "pids.max", next.pid_limit.to_string())
        };
        if let Err(error) = apply() {
            let old_quota = u64::from(previous.cpu_millis)
                .saturating_mul(Self::CPU_PERIOD_MICROS)
                .div_ceil(1000);
            let _ = Self::write(
                &self.path,
                "cpu.max",
                format!("{old_quota} {}", Self::CPU_PERIOD_MICROS),
            );
            let _ = Self::write(
                &self.path,
                "memory.max",
                u64::from(previous.memory_limit_mib)
                    .saturating_mul(1024 * 1024)
                    .to_string(),
            );
            let _ = Self::write(
                &self.path,
                "memory.high",
                u64::from(previous.memory_request_mib)
                    .saturating_mul(1024 * 1024)
                    .to_string(),
            );
            let _ = Self::write(&self.path, "pids.max", previous.pid_limit.to_string());
            return Err(error.context("failed to adjust runtime cgroup; previous limits restored"));
        }
        Ok(())
    }

    fn kill_all(&self) {
        let kill = self.path.join("cgroup.kill");
        if kill.exists() {
            if let Err(error) = fs::write(kill, "1") {
                tracing::warn!(cgroup = %self.path.display(), error = %error, "Failed to kill runtime cgroup");
            }
        }
    }

    fn cleanup(&self) {
        for child in [&self.browser_path, &self.desktop_path]
            .into_iter()
            .flatten()
        {
            match fs::remove_dir(child) {
                Ok(()) => {}
                Err(error) if error.kind() == ErrorKind::NotFound => {}
                Err(error) => {
                    tracing::warn!(cgroup = %child.display(), error = %error, "Runtime child cgroup cleanup deferred")
                }
            }
        }
        match fs::remove_dir(&self.path) {
            Ok(()) => {}
            Err(error) if error.kind() == ErrorKind::NotFound => {}
            Err(error) => {
                tracing::warn!(cgroup = %self.path.display(), error = %error, "Runtime cgroup cleanup deferred")
            }
        }
    }
}

/// Chromium Runtime Supervisor 实现。
pub struct ChromiumRuntimeSupervisor {
    chromium_binary: PathBuf,
    runtimes: Arc<Mutex<HashMap<String, RunningRuntime>>>,
    generations: Arc<Mutex<HashMap<String, u64>>>,
    desktop: Option<DesktopRuntimeConfig>,
    cgroup_v2: Option<CgroupV2Config>,
    metric_system: Arc<Mutex<sysinfo::System>>,
}

impl ChromiumRuntimeSupervisor {
    pub fn new(chromium_binary: PathBuf) -> Self {
        Self {
            chromium_binary,
            runtimes: Arc::new(Mutex::new(HashMap::new())),
            generations: Arc::new(Mutex::new(HashMap::new())),
            desktop: None,
            cgroup_v2: None,
            metric_system: Arc::new(Mutex::new(sysinfo::System::new())),
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

    pub fn with_cgroup_v2(mut self, config: CgroupV2Config) -> anyhow::Result<Self> {
        anyhow::ensure!(
            config.root.join("cgroup.controllers").is_file(),
            "Runtime cgroup root is not a delegated cgroup v2 subtree"
        );
        self.cgroup_v2 = Some(config);
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
        let limits = &spec.resource_limits;
        anyhow::ensure!(
            matches!(
                limits.resource_class.as_str(),
                "L0" | "L1" | "L2" | "L3" | "L4" | "L5"
            ),
            "resource class is invalid"
        );
        anyhow::ensure!(
            limits.resource_class != "L0",
            "L0 cannot start a browser runtime"
        );
        anyhow::ensure!(limits.cpu_millis > 0, "CPU limit is required");
        anyhow::ensure!(
            limits.memory_request_mib > 0 && limits.memory_limit_mib >= limits.memory_request_mib,
            "memory limits are invalid"
        );
        anyhow::ensure!(limits.pid_limit >= 32, "PID limit is invalid");
        anyhow::ensure!(limits.tab_budget > 0, "tab budget is invalid");
        anyhow::ensure!(
            !limits.desktop_required || !spec.display.is_empty(),
            "desktop-required placement has no display"
        );
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

    async fn start_desktop(
        &self,
        spec: &RuntimeSpec,
        cgroup: Option<&RuntimeCgroup>,
    ) -> anyhow::Result<Option<DesktopProcesses>> {
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
        if let Some(cgroup) = cgroup {
            if let Err(error) = xvfb
                .id()
                .ok_or_else(|| anyhow::anyhow!("missing Xvfb pid"))
                .and_then(|pid| cgroup.attach_desktop(pid))
            {
                let _ = xvfb.start_kill();
                return Err(error);
            }
        }
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
        if let Some(cgroup) = cgroup {
            if let Err(error) = vnc
                .id()
                .ok_or_else(|| anyhow::anyhow!("missing x11vnc pid"))
                .and_then(|pid| cgroup.attach_desktop(pid))
            {
                let _ = vnc.start_kill();
                let _ = xvfb.start_kill();
                return Err(error);
            }
        }
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
        tokio::fs::create_dir_all(&spec.cache_dir).await?;
        let cgroup = self
            .cgroup_v2
            .as_ref()
            .map(|config| RuntimeCgroup::prepare(config, &spec))
            .transpose()?;
        let mut desktop = match self.start_desktop(&spec, cgroup.as_ref()).await {
            Ok(desktop) => desktop,
            Err(error) => {
                if let Some(cgroup) = cgroup.as_ref() {
                    cgroup.kill_all();
                    cgroup.cleanup();
                }
                return Err(error);
            }
        };

        let mut command = tokio::process::Command::new(&self.chromium_binary);
        command
            .arg(format!("--user-data-dir={}", spec.profile_dir.display()))
            .arg(format!("--disk-cache-dir={}", spec.cache_dir.display()))
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

        if let Some(proxy_server) = spec.proxy_server.as_ref() {
            command
                .arg(format!("--proxy-server={proxy_server}"))
                .arg("--proxy-bypass-list=<-loopback>");
        }

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
                if let Some(cgroup) = cgroup.as_ref() {
                    cgroup.kill_all();
                    cgroup.cleanup();
                }
                return Err(error.into());
            }
        };
        let pid = child
            .id()
            .ok_or_else(|| anyhow::anyhow!("missing Chromium pid"))?;
        if let Some(cgroup) = cgroup.as_ref() {
            if let Err(error) = cgroup.attach_browser(pid) {
                let _ = child.start_kill();
                if let Some(desktop) = desktop.as_mut() {
                    Self::stop_child(&mut desktop.vnc, Duration::from_secs(2)).await;
                    Self::stop_child(&mut desktop.xvfb, Duration::from_secs(2)).await;
                }
                cgroup.kill_all();
                cgroup.cleanup();
                return Err(error);
            }
        }
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
            if let Some(cgroup) = cgroup.as_ref() {
                cgroup.kill_all();
                cgroup.cleanup();
            }
            return Err(error.context("Chromium started but CDP did not become ready"));
        }
        runtimes.insert(
            spec.session_id.clone(),
            RunningRuntime {
                handle: handle.clone(),
                child,
                desktop,
                cgroup,
                resource_limits: spec.resource_limits.clone(),
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

    async fn adjust_resources(
        &self,
        session_id: &str,
        limits: RuntimeResourceLimits,
    ) -> anyhow::Result<RuntimeResourceAdjustment> {
        let mut runtimes = self.runtimes.lock().await;
        let runtime = runtimes
            .get_mut(session_id)
            .ok_or_else(|| anyhow::anyhow!("runtime not found"))?;
        anyhow::ensure!(
            limits.desktop_required == runtime.resource_limits.desktop_required
                && limits.gpu_required == runtime.resource_limits.gpu_required
                && limits.native_os_required == runtime.resource_limits.native_os_required
                && limits.isolation_required == runtime.resource_limits.isolation_required,
            "online adjustment cannot change execution environment capabilities"
        );
        let previous = runtime.resource_limits.clone();
        let Some(cgroup) = runtime.cgroup.as_ref() else {
            tracing::warn!(
                session_id,
                requested_cpu_millis = limits.cpu_millis,
                applied_cpu_millis = previous.cpu_millis,
                requested_memory_limit_mib = limits.memory_limit_mib,
                applied_memory_limit_mib = previous.memory_limit_mib,
                "Cgroup resource adjustment skipped because enforcement is unavailable"
            );
            return Ok(RuntimeResourceAdjustment {
                previous: previous.clone(),
                applied: previous,
            });
        };
        cgroup.adjust(&previous, &limits)?;
        runtime.resource_limits = limits.clone();
        Ok(RuntimeResourceAdjustment {
            previous,
            applied: limits,
        })
    }

    async fn stop(&self, session_id: &str) -> anyhow::Result<()> {
        let runtime = self.runtimes.lock().await.remove(session_id);
        let Some(mut runtime) = runtime else {
            return Ok(());
        };

        if let Some(cgroup) = runtime.cgroup.as_ref() {
            cgroup.kill_all();
        }
        if runtime.child.try_wait()?.is_none() {
            runtime.child.start_kill()?;
        }
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
        if let Some(cgroup) = runtime.cgroup {
            cgroup.cleanup();
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
                    if let Some(runtime) = runtimes.remove(session_id) {
                        if let Some(cgroup) = runtime.cgroup {
                            cgroup.kill_all();
                            cgroup.cleanup();
                        }
                    }
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
        let (pid, cgroup, cpu_limit_millis) = {
            let runtimes = self.runtimes.lock().await;
            let runtime = runtimes
                .get(session_id)
                .ok_or_else(|| anyhow::anyhow!("runtime not found"))?;
            (
                runtime.handle.pid,
                runtime.cgroup.clone(),
                runtime.resource_limits.cpu_millis,
            )
        };
        let process_id = sysinfo::Pid::from_u32(pid);
        let mut system = self.metric_system.lock().await;
        system.refresh_processes(sysinfo::ProcessesToUpdate::Some(&[process_id]), true);
        let process = system
            .process(process_id)
            .ok_or_else(|| anyhow::anyhow!("runtime process is not visible"))?;
        Ok(RuntimeMetrics {
            pid,
            resident_memory_bytes: cgroup
                .as_ref()
                .and_then(RuntimeCgroup::memory_current_bytes)
                .unwrap_or_else(|| process.memory()),
            virtual_memory_bytes: process.virtual_memory(),
            cpu_usage_percent: process.cpu_usage(),
            cumulative_cpu_usage_micros: cgroup
                .as_ref()
                .and_then(RuntimeCgroup::cumulative_cpu_usage_micros),
            cpu_limit_millis,
            memory_psi_some_avg10: cgroup
                .as_ref()
                .and_then(RuntimeCgroup::memory_psi_some_avg10),
            process_count: cgroup.as_ref().and_then(RuntimeCgroup::process_count),
            cumulative_browser_io_bytes: cgroup
                .as_ref()
                .and_then(RuntimeCgroup::cumulative_browser_io_bytes),
        })
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
                cache_dir: PathBuf::from("/tmp/should-not-be-created-cache"),
                proxy_server: None,
                display: String::new(),
                cdp_port: 9222,
                vnc_port: None,
                resource_limits: RuntimeResourceLimits::local_test_default(),
            })
            .await;
        assert!(result.is_err());
    }

    #[test]
    fn writes_exact_cgroup_v2_limits() {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!("browsercloud-cgroup-v2-{nonce}"));
        fs::create_dir_all(&root).unwrap();
        fs::write(root.join("cgroup.controllers"), "cpu memory pids io").unwrap();
        fs::write(root.join("cgroup.subtree_control"), "").unwrap();
        let spec = RuntimeSpec {
            session_id: "ses_capacity_limits".into(),
            runtime_build_id: "runtime-test".into(),
            profile_dir: root.join("profile"),
            cache_dir: root.join("cache"),
            proxy_server: None,
            display: String::new(),
            cdp_port: 9222,
            vnc_port: None,
            resource_limits: RuntimeResourceLimits {
                resource_class: "L3".into(),
                cpu_millis: 1_250,
                memory_request_mib: 1_024,
                memory_limit_mib: 2_048,
                pid_limit: 256,
                tab_budget: 16,
                desktop_required: false,
                gpu_required: false,
                native_os_required: false,
                isolation_required: false,
            },
        };
        let cgroup = RuntimeCgroup::prepare(&CgroupV2Config { root: root.clone() }, &spec).unwrap();
        assert_eq!(
            fs::read_to_string(cgroup.path.join("cpu.max")).unwrap(),
            "125000 100000"
        );
        assert_eq!(
            fs::read_to_string(cgroup.path.join("memory.high")).unwrap(),
            "1073741824"
        );
        assert_eq!(
            fs::read_to_string(cgroup.path.join("memory.max")).unwrap(),
            "2147483648"
        );
        assert_eq!(
            fs::read_to_string(cgroup.path.join("pids.max")).unwrap(),
            "256"
        );
        fs::write(cgroup.path.join("memory.current"), "805306368").unwrap();
        fs::write(cgroup.path.join("pids.current"), "80").unwrap();
        let adjusted = RuntimeResourceLimits {
            resource_class: "L3".into(),
            cpu_millis: 2_000,
            memory_request_mib: 1_536,
            memory_limit_mib: 3_072,
            pid_limit: 384,
            tab_budget: 20,
            desktop_required: false,
            gpu_required: false,
            native_os_required: false,
            isolation_required: false,
        };
        cgroup.adjust(&spec.resource_limits, &adjusted).unwrap();
        assert_eq!(
            fs::read_to_string(cgroup.path.join("cpu.max")).unwrap(),
            "200000 100000"
        );
        assert_eq!(
            fs::read_to_string(cgroup.path.join("memory.high")).unwrap(),
            "1610612736"
        );
        assert_eq!(
            fs::read_to_string(cgroup.path.join("memory.max")).unwrap(),
            "3221225472"
        );
        assert_eq!(
            fs::read_to_string(cgroup.path.join("pids.max")).unwrap(),
            "384"
        );
        assert_eq!(
            fs::read_to_string(root.join("cgroup.subtree_control")).unwrap(),
            "+cpu +memory +pids +io"
        );
        let browser_path = cgroup.browser_path.as_ref().unwrap();
        let desktop_path = cgroup.desktop_path.as_ref().unwrap();
        fs::write(
            browser_path.join("io.stat"),
            "8:0 rbytes=1024 wbytes=2048 rios=2 wios=3\n8:16 rbytes=4096 wbytes=8192",
        )
        .unwrap();
        assert_eq!(cgroup.cumulative_browser_io_bytes(), Some(15_360));
        cgroup.attach_browser(42).unwrap();
        assert_eq!(
            fs::read_to_string(browser_path.join("cgroup.procs")).unwrap(),
            "42"
        );
        cgroup.attach_desktop(43).unwrap();
        assert_eq!(
            fs::read_to_string(desktop_path.join("cgroup.procs")).unwrap(),
            "43"
        );
        fs::remove_dir_all(root).unwrap();
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
                cache_dir: profile_dir.join("EphemeralCache"),
                proxy_server: None,
                display: String::new(),
                cdp_port,
                vnc_port: None,
                resource_limits: RuntimeResourceLimits::local_test_default(),
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
