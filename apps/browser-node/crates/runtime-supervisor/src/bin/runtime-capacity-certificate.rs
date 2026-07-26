use anyhow::Context;
use runtime_supervisor::{
    ChromiumRuntimeSupervisor, RuntimeHealth, RuntimeResourceLimits, RuntimeSpec, RuntimeSupervisor,
};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use sysinfo::{Pid, ProcessesToUpdate, System};
use tokio::task::JoinSet;

const MINIMUM_CERTIFICATE_CYCLES: usize = 500;
const RUNNER_RSS_GROWTH_LIMIT_BYTES: i64 = 64 * 1024 * 1024;
const RUNNER_FD_GROWTH_LIMIT: i64 = 8;
const RUNTIME_TREE_RSS_LIMIT_BYTES: u64 = 2 * 1024 * 1024 * 1024;
const RUNTIME_PROCESS_LIMIT: usize = 64;
const START_P99_LIMIT_MILLIS: u64 = 15_000;
const STOP_P99_LIMIT_MILLIS: u64 = 10_000;

#[derive(Debug)]
struct Options {
    chromium: PathBuf,
    output: PathBuf,
    cycles: usize,
    concurrency: usize,
    build_id: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Environment {
    os: String,
    architecture: String,
    logical_cpus: usize,
    chromium_version: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct LoadModel {
    cycles: usize,
    concurrency: usize,
    mode: &'static str,
    session_reuse: bool,
    profile_reuse: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct LatencySummary {
    minimum_millis: u64,
    p50_millis: u64,
    p95_millis: u64,
    p99_millis: u64,
    maximum_millis: u64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ResourceSummary {
    peak_runtime_tree_rss_bytes: u64,
    peak_runtime_processes: usize,
    peak_concurrent_sessions: usize,
    runner_rss_before_bytes: u64,
    runner_rss_after_bytes: u64,
    runner_rss_growth_bytes: i64,
    runner_fds_before: usize,
    runner_fds_after: usize,
    runner_fd_growth: i64,
    residual_processes: usize,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct CertificatePayload {
    schema_version: u8,
    scope: &'static str,
    build_id: String,
    generated_at_epoch_seconds: u64,
    environment: Environment,
    load_model: LoadModel,
    start_latency: LatencySummary,
    stop_latency: LatencySummary,
    resources: ResourceSummary,
    gates: BTreeMap<String, bool>,
    failures: Vec<String>,
    passed: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Certificate {
    #[serde(flatten)]
    payload: CertificatePayload,
    certificate_hash: String,
}

#[derive(Debug)]
struct CycleSample {
    start_millis: u64,
    stop_millis: u64,
    tree_rss_bytes: u64,
    process_count: usize,
}

#[derive(Debug)]
struct StartedCycle {
    cycle: usize,
    session_id: String,
    profile_dir: PathBuf,
    cdp_port: u16,
    pid: u32,
    start_millis: u64,
    tree_rss_bytes: u64,
    process_count: usize,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let options = parse_options()?;
    anyhow::ensure!(
        options.cycles >= MINIMUM_CERTIFICATE_CYCLES,
        "a production lifecycle certificate requires at least {MINIMUM_CERTIFICATE_CYCLES} cycles"
    );
    anyhow::ensure!(
        options.concurrency >= 1 && options.concurrency <= 64,
        "concurrency must be between 1 and 64"
    );
    anyhow::ensure!(
        options.concurrency == 1 || options.concurrency >= 4,
        "a concurrent Browser Density certificate requires at least 4 sessions"
    );
    anyhow::ensure!(
        options.chromium.is_file(),
        "Chromium binary does not exist: {}",
        options.chromium.display()
    );

    let chromium_version = chromium_version(&options.chromium)?;
    let run_root = std::env::temp_dir().join(format!(
        "browsercloud-runtime-capacity-{}-{}",
        std::process::id(),
        unix_nanos()
    ));
    std::fs::create_dir_all(&run_root)?;

    let supervisor = Arc::new(ChromiumRuntimeSupervisor::new(options.chromium.clone()));
    let runner_pid = std::process::id();
    let runner_rss_before = process_rss(runner_pid);
    let runner_fds_before = open_file_descriptors();
    let mut samples = Vec::with_capacity(options.cycles);
    let mut failures = Vec::new();
    let mut residual_processes = 0;
    let mut peak_concurrent_sessions = 0;
    let mut peak_concurrent_rss_bytes = 0;
    let mut peak_concurrent_processes = 0;

    for batch_start in (0..options.cycles).step_by(options.concurrency) {
        let batch_end = (batch_start + options.concurrency).min(options.cycles);
        match run_batch(Arc::clone(&supervisor), &run_root, batch_start..batch_end).await {
            Ok(batch) => {
                peak_concurrent_sessions = peak_concurrent_sessions.max(batch.len());
                peak_concurrent_rss_bytes = peak_concurrent_rss_bytes.max(
                    batch
                        .iter()
                        .map(|sample| sample.tree_rss_bytes)
                        .sum::<u64>(),
                );
                peak_concurrent_processes = peak_concurrent_processes.max(
                    batch
                        .iter()
                        .map(|sample| sample.process_count)
                        .sum::<usize>(),
                );
                samples.extend(batch);
            }
            Err(error) => {
                failures.push(format!("batch {batch_start}..{batch_end}: {error:#}"));
                break;
            }
        }
        for cycle in batch_start..batch_end {
            let profile_dir = run_root.join(format!("cycle-{cycle:04}"));
            let residual = matching_profile_processes(&profile_dir);
            if !residual.is_empty() {
                residual_processes += residual.len();
                failures.push(format!(
                    "cycle {cycle}: residual profile processes {:?}",
                    residual
                ));
                break;
            }
            if let Err(error) = std::fs::remove_dir_all(&profile_dir) {
                failures.push(format!("cycle {cycle}: profile cleanup failed: {error}"));
                break;
            }
        }
        if !failures.is_empty() {
            break;
        }
        if batch_end % 25 == 0 || batch_end == options.cycles {
            println!("runtime_capacity_progress={}/{}", batch_end, options.cycles);
        }
    }

    supervisor.stop_all().await;
    let _ = std::fs::remove_dir_all(&run_root);
    let runner_rss_after = process_rss(runner_pid);
    let runner_fds_after = open_file_descriptors();

    let start_latencies = samples
        .iter()
        .map(|sample| sample.start_millis)
        .collect::<Vec<_>>();
    let stop_latencies = samples
        .iter()
        .map(|sample| sample.stop_millis)
        .collect::<Vec<_>>();
    let start_latency = latency_summary(start_latencies);
    let stop_latency = latency_summary(stop_latencies);
    let peak_runtime_tree_rss_bytes = if options.concurrency > 1 {
        peak_concurrent_rss_bytes
    } else {
        samples
            .iter()
            .map(|sample| sample.tree_rss_bytes)
            .max()
            .unwrap_or_default()
    };
    let peak_runtime_processes = if options.concurrency > 1 {
        peak_concurrent_processes
    } else {
        samples
            .iter()
            .map(|sample| sample.process_count)
            .max()
            .unwrap_or_default()
    };
    let runner_rss_growth = runner_rss_after as i64 - runner_rss_before as i64;
    let runner_fd_growth = runner_fds_after as i64 - runner_fds_before as i64;

    let mut gates = BTreeMap::new();
    gates.insert(
        "allCyclesCompleted".to_owned(),
        samples.len() == options.cycles,
    );
    gates.insert("noCycleFailures".to_owned(), failures.is_empty());
    gates.insert("noResidualProcesses".to_owned(), residual_processes == 0);
    gates.insert(
        "runnerRssGrowthWithinLimit".to_owned(),
        runner_rss_growth <= RUNNER_RSS_GROWTH_LIMIT_BYTES,
    );
    gates.insert(
        "runnerFdGrowthWithinLimit".to_owned(),
        runner_fd_growth <= RUNNER_FD_GROWTH_LIMIT,
    );
    gates.insert(
        "runtimeTreeRssWithinLimit".to_owned(),
        peak_runtime_tree_rss_bytes
            <= RUNTIME_TREE_RSS_LIMIT_BYTES.saturating_mul(options.concurrency as u64),
    );
    gates.insert(
        "runtimeProcessCountWithinLimit".to_owned(),
        peak_runtime_processes <= RUNTIME_PROCESS_LIMIT * options.concurrency,
    );
    gates.insert(
        "requestedConcurrencyReached".to_owned(),
        peak_concurrent_sessions == options.concurrency.min(options.cycles),
    );
    gates.insert(
        "startP99WithinLimit".to_owned(),
        start_latency.p99_millis <= START_P99_LIMIT_MILLIS,
    );
    gates.insert(
        "stopP99WithinLimit".to_owned(),
        stop_latency.p99_millis <= STOP_P99_LIMIT_MILLIS,
    );
    let passed = gates.values().all(|passed| *passed);
    let payload = CertificatePayload {
        schema_version: 1,
        scope: if options.concurrency == 1 {
            "REAL_CHROMIUM_LIFECYCLE_SINGLE_NODE"
        } else {
            "REAL_CHROMIUM_CONCURRENT_DENSITY_SINGLE_NODE"
        },
        build_id: options.build_id,
        generated_at_epoch_seconds: unix_seconds(),
        environment: Environment {
            os: std::env::consts::OS.to_owned(),
            architecture: std::env::consts::ARCH.to_owned(),
            logical_cpus: std::thread::available_parallelism()
                .map(usize::from)
                .unwrap_or(1),
            chromium_version,
        },
        load_model: LoadModel {
            cycles: options.cycles,
            concurrency: options.concurrency,
            mode: if options.concurrency == 1 {
                "SEQUENTIAL_HEADLESS"
            } else {
                "CONCURRENT_HEADLESS"
            },
            session_reuse: options.concurrency == 1,
            profile_reuse: false,
        },
        start_latency,
        stop_latency,
        resources: ResourceSummary {
            peak_runtime_tree_rss_bytes,
            peak_runtime_processes,
            peak_concurrent_sessions,
            runner_rss_before_bytes: runner_rss_before,
            runner_rss_after_bytes: runner_rss_after,
            runner_rss_growth_bytes: runner_rss_growth,
            runner_fds_before,
            runner_fds_after,
            runner_fd_growth,
            residual_processes,
        },
        gates,
        failures,
        passed,
    };
    let canonical_payload = serde_json::to_vec(&payload)?;
    let certificate = Certificate {
        certificate_hash: hex_sha256(&canonical_payload),
        payload,
    };
    if let Some(parent) = options.output.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&options.output, serde_json::to_vec_pretty(&certificate)?)?;

    println!(
        "RUNTIME_CAPACITY_CERTIFICATE_{} cycles={} concurrency={} start_p99_ms={} stop_p99_ms={} rss_growth_bytes={} fd_growth={} output={}",
        if passed { "OK" } else { "FAILED" },
        samples.len(),
        options.concurrency,
        certificate.payload.start_latency.p99_millis,
        certificate.payload.stop_latency.p99_millis,
        runner_rss_growth,
        runner_fd_growth,
        options.output.display()
    );
    anyhow::ensure!(passed, "runtime lifecycle capacity certificate failed");
    Ok(())
}

async fn run_batch(
    supervisor: Arc<ChromiumRuntimeSupervisor>,
    run_root: &Path,
    cycles: std::ops::Range<usize>,
) -> anyhow::Result<Vec<CycleSample>> {
    let mut ports = Vec::with_capacity(cycles.len());
    while ports.len() < cycles.len() {
        let port = reserve_port()?;
        if !ports.contains(&port) {
            ports.push(port);
        }
    }

    let mut starts = JoinSet::new();
    for (offset, cycle) in cycles.clone().enumerate() {
        let supervisor = Arc::clone(&supervisor);
        let profile_dir = run_root.join(format!("cycle-{cycle:04}"));
        let cdp_port = ports[offset];
        starts.spawn(async move {
            let session_id = format!("ses_capacity_{cycle:016}");
            let started_at = Instant::now();
            let handle = supervisor
                .start(RuntimeSpec {
                    session_id: session_id.clone(),
                    runtime_build_id: "runtime-real-capacity".to_owned(),
                    profile_dir: profile_dir.clone(),
                    cache_dir: profile_dir.join("EphemeralCache"),
                    proxy_server: None,
                    display: String::new(),
                    cdp_port,
                    vnc_port: None,
                    resource_limits: RuntimeResourceLimits::local_test_default(),
                })
                .await
                .with_context(|| format!("cycle {cycle} failed to start"))?;
            anyhow::ensure!(
                supervisor.health(&session_id).await? == RuntimeHealth::Healthy,
                "cycle {cycle} runtime did not become healthy"
            );
            let metrics = supervisor.metrics(&session_id).await?;
            anyhow::ensure!(
                metrics.resident_memory_bytes > 0,
                "cycle {cycle} runtime RSS was not sampled"
            );
            let (tree_rss_bytes, process_count) = profile_tree_resources(&profile_dir);
            Ok::<_, anyhow::Error>(StartedCycle {
                cycle,
                session_id,
                profile_dir,
                cdp_port,
                pid: handle.pid,
                start_millis: elapsed_millis(started_at),
                tree_rss_bytes: tree_rss_bytes.max(metrics.resident_memory_bytes),
                process_count: process_count.max(1),
            })
        });
    }

    let mut started = Vec::with_capacity(cycles.len());
    let mut start_failure = None;
    while let Some(result) = starts.join_next().await {
        match result {
            Ok(Ok(cycle)) => started.push(cycle),
            Ok(Err(error)) => start_failure = Some(error),
            Err(error) => start_failure = Some(anyhow::anyhow!("start task failed: {error}")),
        }
    }
    if let Some(error) = start_failure {
        supervisor.stop_all().await;
        return Err(error);
    }
    anyhow::ensure!(
        started.len() == cycles.len(),
        "not all concurrent runtimes reached healthy"
    );

    let mut stops = JoinSet::new();
    for cycle in started {
        let supervisor = Arc::clone(&supervisor);
        stops.spawn(async move {
            let stopped_at = Instant::now();
            supervisor
                .stop(&cycle.session_id)
                .await
                .with_context(|| format!("cycle {} failed to stop", cycle.cycle))?;
            let stop_millis = elapsed_millis(stopped_at);
            wait_for_process_exit(cycle.pid, Duration::from_secs(3)).await?;
            wait_for_profile_processes_exit(&cycle.profile_dir, Duration::from_secs(3)).await?;
            anyhow::ensure!(
                std::net::TcpListener::bind(("127.0.0.1", cycle.cdp_port)).is_ok(),
                "cycle {} CDP port remained bound after runtime stop",
                cycle.cycle
            );
            Ok::<_, anyhow::Error>((
                cycle.cycle,
                CycleSample {
                    start_millis: cycle.start_millis,
                    stop_millis,
                    tree_rss_bytes: cycle.tree_rss_bytes,
                    process_count: cycle.process_count,
                },
            ))
        });
    }

    let mut samples = Vec::with_capacity(cycles.len());
    while let Some(result) = stops.join_next().await {
        let (cycle, sample) = result.context("stop task failed")??;
        samples.push((cycle, sample));
    }
    samples.sort_by_key(|(cycle, _)| *cycle);
    Ok(samples.into_iter().map(|(_, sample)| sample).collect())
}

fn parse_options() -> anyhow::Result<Options> {
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    let mut chromium = None;
    let mut output = None;
    let mut cycles = MINIMUM_CERTIFICATE_CYCLES;
    let mut concurrency = 1;
    let mut build_id = None;
    let mut index = 0;
    while index < arguments.len() {
        let value = arguments
            .get(index + 1)
            .with_context(|| format!("missing value for {}", arguments[index]))?;
        match arguments[index].as_str() {
            "--chromium" => chromium = Some(PathBuf::from(value)),
            "--output" => output = Some(PathBuf::from(value)),
            "--cycles" => cycles = value.parse()?,
            "--concurrency" => concurrency = value.parse()?,
            "--build-id" => build_id = Some(value.to_owned()),
            unknown => anyhow::bail!("unknown option {unknown}"),
        }
        index += 2;
    }
    let build_id = build_id.context("--build-id is required")?;
    anyhow::ensure!(
        !build_id.is_empty() && build_id.len() <= 128,
        "build id must contain 1 to 128 characters"
    );
    Ok(Options {
        chromium: chromium.context("--chromium is required")?,
        output: output.context("--output is required")?,
        cycles,
        concurrency,
        build_id,
    })
}

fn chromium_version(chromium: &Path) -> anyhow::Result<String> {
    let output = std::process::Command::new(chromium)
        .arg("--version")
        .output()
        .context("failed to read Chromium version")?;
    anyhow::ensure!(output.status.success(), "Chromium --version failed");
    let version = String::from_utf8(output.stdout)?.trim().to_owned();
    anyhow::ensure!(!version.is_empty(), "Chromium version is empty");
    Ok(version)
}

fn profile_tree_resources(profile_dir: &Path) -> (u64, usize) {
    let needle = profile_dir.to_string_lossy();
    let mut system = System::new_all();
    system.refresh_all();
    system
        .processes()
        .values()
        .filter(|process| {
            process
                .cmd()
                .iter()
                .any(|argument| argument.to_string_lossy().contains(needle.as_ref()))
        })
        .fold((0_u64, 0_usize), |(memory, count), process| {
            (memory.saturating_add(process.memory()), count + 1)
        })
}

fn matching_profile_processes(profile_dir: &Path) -> Vec<u32> {
    let needle = profile_dir.to_string_lossy();
    let mut system = System::new_all();
    system.refresh_all();
    let mut processes = system
        .processes()
        .iter()
        .filter_map(|(pid, process)| {
            process
                .cmd()
                .iter()
                .any(|argument| argument.to_string_lossy().contains(needle.as_ref()))
                .then_some(pid.as_u32())
        })
        .collect::<Vec<_>>();
    processes.sort_unstable();
    processes
}

async fn wait_for_process_exit(pid: u32, deadline: Duration) -> anyhow::Result<()> {
    let expires_at = Instant::now() + deadline;
    loop {
        let mut system = System::new();
        system.refresh_processes(ProcessesToUpdate::Some(&[Pid::from_u32(pid)]), true);
        if system.process(Pid::from_u32(pid)).is_none() {
            return Ok(());
        }
        anyhow::ensure!(
            Instant::now() < expires_at,
            "runtime pid {pid} remained after stop"
        );
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
}

async fn wait_for_profile_processes_exit(
    profile_dir: &Path,
    deadline: Duration,
) -> anyhow::Result<()> {
    let expires_at = Instant::now() + deadline;
    loop {
        let residual = matching_profile_processes(profile_dir);
        if residual.is_empty() {
            return Ok(());
        }
        anyhow::ensure!(
            Instant::now() < expires_at,
            "profile processes remained after stop: {residual:?}"
        );
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
}

fn process_rss(pid: u32) -> u64 {
    let process_id = Pid::from_u32(pid);
    let mut system = System::new();
    system.refresh_processes(ProcessesToUpdate::Some(&[process_id]), true);
    system
        .process(process_id)
        .map(sysinfo::Process::memory)
        .unwrap_or_default()
}

#[cfg(unix)]
fn open_file_descriptors() -> usize {
    let maximum = unsafe { libc::getdtablesize() };
    (0..maximum)
        .filter(|descriptor| unsafe { libc::fcntl(*descriptor, libc::F_GETFD) } != -1)
        .count()
}

#[cfg(not(unix))]
fn open_file_descriptors() -> usize {
    0
}

fn reserve_port() -> anyhow::Result<u16> {
    let listener = std::net::TcpListener::bind("127.0.0.1:0")?;
    Ok(listener.local_addr()?.port())
}

fn latency_summary(mut values: Vec<u64>) -> LatencySummary {
    values.sort_unstable();
    LatencySummary {
        minimum_millis: values.first().copied().unwrap_or_default(),
        p50_millis: percentile(&values, 0.50),
        p95_millis: percentile(&values, 0.95),
        p99_millis: percentile(&values, 0.99),
        maximum_millis: values.last().copied().unwrap_or_default(),
    }
}

fn percentile(values: &[u64], percentile: f64) -> u64 {
    if values.is_empty() {
        return 0;
    }
    let index = (percentile * values.len() as f64).ceil() as usize - 1;
    values[index.min(values.len() - 1)]
}

fn elapsed_millis(started_at: Instant) -> u64 {
    u64::try_from(started_at.elapsed().as_millis()).unwrap_or(u64::MAX)
}

fn unix_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn unix_nanos() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos()
}

fn hex_sha256(value: &[u8]) -> String {
    format!("{:x}", Sha256::digest(value))
}
