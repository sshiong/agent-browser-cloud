//! Input Sandbox。
//!
//! 负责管理桌面输入，包括鼠标、键盘和触控。

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

/// 输入键。
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum InputKey {
    Shift,
    Control,
    Alt,
    Meta,
    Character(String),
}

/// 输入账本。
#[derive(Debug, Default, Serialize, Deserialize)]
pub struct InputLedger {
    pub pressed_keys: HashSet<InputKey>,
    pub pressed_buttons: HashSet<u8>,
    pub active_drag: bool,
    pub last_sequence: u64,
}

impl InputLedger {
    pub fn press_key(&mut self, key: InputKey) {
        self.pressed_keys.insert(key);
    }

    pub fn release_key(&mut self, key: &InputKey) {
        self.pressed_keys.remove(key);
    }

    pub fn press_button(&mut self, button: u8) {
        self.pressed_buttons.insert(button);
    }

    pub fn release_button(&mut self, button: u8) {
        self.pressed_buttons.remove(&button);
    }

    pub fn start_drag(&mut self) {
        self.active_drag = true;
    }

    pub fn end_drag(&mut self) {
        self.active_drag = false;
    }

    pub fn release_all(&mut self) {
        self.pressed_keys.clear();
        self.pressed_buttons.clear();
        self.active_drag = false;
    }

    pub fn is_key_pressed(&self, key: &InputKey) -> bool {
        self.pressed_keys.contains(key)
    }

    pub fn has_any_input(&self) -> bool {
        !self.pressed_keys.is_empty() || !self.pressed_buttons.is_empty() || self.active_drag
    }
}

/// 桌面输入 trait。
#[async_trait]
pub trait DesktopInput: Send + Sync {
    async fn mouse_move(&self, x: i32, y: i32, sequence: u64) -> anyhow::Result<()>;
    async fn mouse_down(&self, button: u8, sequence: u64) -> anyhow::Result<()>;
    async fn mouse_up(&self, button: u8, sequence: u64) -> anyhow::Result<()>;
    async fn key_down(&self, key: InputKey, sequence: u64) -> anyhow::Result<()>;
    async fn key_up(&self, key: InputKey, sequence: u64) -> anyhow::Result<()>;
    async fn release_all(&self) -> anyhow::Result<()>;
}

/// 释放看门狗。
pub async fn run_release_watchdog<I: DesktopInput>(
    input: &I,
    ledger: &InputLedger,
    heartbeat_age: std::time::Duration,
) -> anyhow::Result<()> {
    if heartbeat_age > std::time::Duration::from_secs(5)
        && (!ledger.pressed_keys.is_empty()
            || !ledger.pressed_buttons.is_empty()
            || ledger.active_drag)
    {
        tracing::warn!("Input watchdog triggered, releasing all inputs");
        input.release_all().await?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn should_track_pressed_keys() {
        let mut ledger = InputLedger::default();

        ledger.press_key(InputKey::Character("a".into()));
        ledger.press_key(InputKey::Shift);

        assert!(ledger.is_key_pressed(&InputKey::Character("a".into())));
        assert!(ledger.is_key_pressed(&InputKey::Shift));
        assert!(!ledger.is_key_pressed(&InputKey::Control));
    }

    #[test]
    fn should_release_all_keys() {
        let mut ledger = InputLedger::default();

        ledger.press_key(InputKey::Character("a".into()));
        ledger.press_key(InputKey::Shift);
        ledger.press_button(1);

        ledger.release_all();

        assert!(ledger.pressed_keys.is_empty());
        assert!(ledger.pressed_buttons.is_empty());
    }

    #[test]
    fn should_detect_active_drag() {
        let mut ledger = InputLedger::default();

        ledger.press_button(1);
        ledger.start_drag();

        assert!(ledger.active_drag);

        ledger.release_button(1);
        ledger.end_drag();

        assert!(!ledger.active_drag);
    }
}
