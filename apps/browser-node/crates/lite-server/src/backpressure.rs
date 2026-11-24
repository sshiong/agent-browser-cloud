//! Graded host-pressure backpressure controller (Section 8.1).
//!
//! Avoids closing a node on transient single-sample I/O PSI spikes by applying EWMA smoothing,
//! P95 window tracking, hysteresis (distinct enter/exit thresholds), consecutive sample
//! confirmation, and recovery cooldown.

use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum BackpressureState {
    Normal,
    Degraded,
    QueueOnly,
    NoNewSessions,
    Recovering,
    Closed,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PressureSample {
    /// Linux PSI `some avg10` percentage (0.0..=100.0) or 0.0 on platforms without `/proc/pressure`.
    pub io_psi_some10: f64,
    /// Host memory utilization percentage (0.0..=100.0).
    pub memory_used_percent: f64,
    /// Storage writable health flag.
    pub storage_healthy: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackpressureSnapshot {
    pub state: BackpressureState,
    pub io_psi_ewma: f64,
    pub io_psi_p95: f64,
    pub memory_ewma: f64,
    pub memory_p95: f64,
    pub accepts_new_sessions: bool,
    pub reason: Option<String>,
}

pub struct GradedBackpressure {
    state: BackpressureState,
    io_ewma: f64,
    mem_ewma: f64,
    io_window: VecDeque<f64>,
    mem_window: VecDeque<f64>,
    window_size: usize,
    consecutive_high: u32,
    consecutive_low: u32,
    state_changed_at: Instant,
    recovery_cooldown: Duration,
    last_reason: Option<String>,
}

impl GradedBackpressure {
    pub fn new(window_size: usize, recovery_cooldown: Duration) -> Self {
        Self {
            state: BackpressureState::Normal,
            io_ewma: 0.0,
            mem_ewma: 0.0,
            io_window: VecDeque::with_capacity(window_size.max(4)),
            mem_window: VecDeque::with_capacity(window_size.max(4)),
            window_size: window_size.max(4),
            consecutive_high: 0,
            consecutive_low: 0,
            state_changed_at: Instant::now(),
            recovery_cooldown,
            last_reason: None,
        }
    }

    pub fn observe(&mut self, sample: PressureSample, now: Instant) -> BackpressureSnapshot {
        const ALPHA: f64 = 0.35;
        if self.io_window.is_empty() {
            self.io_ewma = sample.io_psi_some10;
            self.mem_ewma = sample.memory_used_percent;
        } else {
            self.io_ewma = ALPHA * sample.io_psi_some10 + (1.0 - ALPHA) * self.io_ewma;
            self.mem_ewma = ALPHA * sample.memory_used_percent + (1.0 - ALPHA) * self.mem_ewma;
        }
        push_bounded(&mut self.io_window, sample.io_psi_some10, self.window_size);
        push_bounded(
            &mut self.mem_window,
            sample.memory_used_percent,
            self.window_size,
        );

        if !sample.storage_healthy {
            self.transition(
                BackpressureState::Closed,
                now,
                Some("STORAGE_UNHEALTHY".to_owned()),
            );
            return self.snapshot();
        }

        let io_p95 = percentile95(&self.io_window);
        let mem_p95 = percentile95(&self.mem_window);

        let severe =
            (self.io_ewma >= 55.0 && io_p95 >= 65.0) || (self.mem_ewma >= 90.0 && mem_p95 >= 92.0);
        let queued =
            (self.io_ewma >= 35.0 && io_p95 >= 45.0) || (self.mem_ewma >= 84.0 && mem_p95 >= 86.0);
        let degraded = self.io_ewma >= 18.0 || self.mem_ewma >= 76.0;
        let recovered = self.io_ewma <= 12.0 && self.mem_ewma <= 70.0;

        if severe || queued || degraded {
            self.consecutive_high = self.consecutive_high.saturating_add(1);
            self.consecutive_low = 0;
        } else if recovered {
            self.consecutive_low = self.consecutive_low.saturating_add(1);
            self.consecutive_high = 0;
        } else {
            self.consecutive_high = 0;
            self.consecutive_low = 0;
        }

        // Require at least 3 consecutive high samples before escalating so transient I/O bursts
        // do not prematurely reject sessions.
        if severe && self.consecutive_high >= 3 {
            self.transition(
                BackpressureState::NoNewSessions,
                now,
                Some("SUSTAINED_HIGH_PRESSURE".to_owned()),
            );
        } else if queued && self.consecutive_high >= 3 {
            if self.state != BackpressureState::NoNewSessions {
                self.transition(
                    BackpressureState::QueueOnly,
                    now,
                    Some("ELEVATED_PRESSURE_QUEUE".to_owned()),
                );
            }
        } else if degraded && self.consecutive_high >= 2 {
            if matches!(
                self.state,
                BackpressureState::Normal | BackpressureState::Recovering
            ) {
                self.transition(
                    BackpressureState::Degraded,
                    now,
                    Some("MODERATE_PRESSURE".to_owned()),
                );
            }
        } else if recovered && self.consecutive_low >= 3 {
            match self.state {
                BackpressureState::Closed
                | BackpressureState::NoNewSessions
                | BackpressureState::QueueOnly
                | BackpressureState::Degraded => {
                    self.transition(
                        BackpressureState::Recovering,
                        now,
                        Some("PRESSURE_RECOVERING".to_owned()),
                    );
                }
                BackpressureState::Recovering => {
                    if now.duration_since(self.state_changed_at) >= self.recovery_cooldown {
                        self.transition(BackpressureState::Normal, now, None);
                    }
                }
                BackpressureState::Normal => {}
            }
        }

        self.snapshot()
    }

    pub fn snapshot(&self) -> BackpressureSnapshot {
        BackpressureSnapshot {
            state: self.state,
            io_psi_ewma: round2(self.io_ewma),
            io_psi_p95: round2(percentile95(&self.io_window)),
            memory_ewma: round2(self.mem_ewma),
            memory_p95: round2(percentile95(&self.mem_window)),
            accepts_new_sessions: matches!(
                self.state,
                BackpressureState::Normal
                    | BackpressureState::Degraded
                    | BackpressureState::QueueOnly
                    | BackpressureState::Recovering
            ),
            reason: self.last_reason.clone(),
        }
    }

    fn transition(&mut self, next: BackpressureState, now: Instant, reason: Option<String>) {
        if self.state != next {
            self.state = next;
            self.state_changed_at = now;
            self.consecutive_high = 0;
            self.consecutive_low = 0;
        }
        self.last_reason = reason;
    }
}

/// Reads Linux `/proc/pressure/io` `some avg10` when available; returns `0.0` on macOS/non-PSI hosts.
pub fn read_host_io_psi_some10() -> f64 {
    let Ok(text) = std::fs::read_to_string("/proc/pressure/io") else {
        return 0.0;
    };
    for line in text.lines() {
        if line.starts_with("some ") {
            for part in line.split_whitespace() {
                if let Some(val) = part.strip_prefix("avg10=") {
                    if let Ok(parsed) = val.parse::<f64>() {
                        return parsed.clamp(0.0, 100.0);
                    }
                }
            }
        }
    }
    0.0
}

fn push_bounded(window: &mut VecDeque<f64>, value: f64, limit: usize) {
    if window.len() == limit {
        window.pop_front();
    }
    window.push_back(value);
}

fn percentile95(window: &VecDeque<f64>) -> f64 {
    if window.is_empty() {
        return 0.0;
    }
    let mut sorted: Vec<f64> = window.iter().copied().collect();
    sorted.sort_by(|a, b| a.total_cmp(b));
    let idx = ((sorted.len() as f64 * 0.95).ceil() as usize).saturating_sub(1);
    sorted[idx.min(sorted.len() - 1)]
}

fn round2(value: f64) -> f64 {
    (value * 100.0).round() / 100.0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ignores_single_io_spike_and_recovers_with_hysteresis_and_cooldown() {
        let start = Instant::now();
        let mut bp = GradedBackpressure::new(8, Duration::from_secs(10));

        // Single I/O spike must NOT close or reject new sessions.
        let snap = bp.observe(
            PressureSample {
                io_psi_some10: 85.0,
                memory_used_percent: 45.0,
                storage_healthy: true,
            },
            start,
        );
        assert_eq!(snap.state, BackpressureState::Normal);
        assert!(snap.accepts_new_sessions);

        // Sustained high I/O escalates to NO_NEW_SESSIONS (not CLOSED while storage is healthy).
        for i in 1..=5 {
            bp.observe(
                PressureSample {
                    io_psi_some10: 80.0,
                    memory_used_percent: 55.0,
                    storage_healthy: true,
                },
                start + Duration::from_secs(i),
            );
        }
        let high = bp.snapshot();
        assert_eq!(high.state, BackpressureState::NoNewSessions);
        assert!(!high.accepts_new_sessions);

        // Sustained low pressure moves through RECOVERING then NORMAL after cooldown.
        for i in 6..=15 {
            bp.observe(
                PressureSample {
                    io_psi_some10: 1.0,
                    memory_used_percent: 40.0,
                    storage_healthy: true,
                },
                start + Duration::from_secs(i),
            );
        }
        assert_eq!(bp.snapshot().state, BackpressureState::Recovering);
        let normal = bp.observe(
            PressureSample {
                io_psi_some10: 1.0,
                memory_used_percent: 40.0,
                storage_healthy: true,
            },
            start + Duration::from_secs(30),
        );
        assert_eq!(normal.state, BackpressureState::Normal);
        assert!(normal.accepts_new_sessions);
    }
}
