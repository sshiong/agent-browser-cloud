//! Input Sandbox。
//!
//! 负责管理桌面输入，包括鼠标、键盘和触控。

use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::sync::Arc;
use std::time::Instant;
use tokio::sync::{Mutex, RwLock};
use tokio::time::{timeout, Duration};
use tokio_tungstenite::tungstenite::Message;

/// 输入键。
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum InputKey {
    Shift,
    Control,
    Alt,
    Meta,
    Enter,
    Tab,
    Escape,
    Backspace,
    Delete,
    ArrowUp,
    ArrowDown,
    ArrowLeft,
    ArrowRight,
    Character(String),
}

/// 输入账本。
#[derive(Debug, Default, Serialize, Deserialize)]
pub struct InputLedger {
    pub pressed_keys: HashSet<InputKey>,
    pub pressed_buttons: HashSet<u8>,
    pub active_drag: bool,
    pub last_sequence: u64,
    pub pointer_x: i32,
    pub pointer_y: i32,
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
    async fn insert_text(&self, text: &str, sequence: u64) -> anyhow::Result<()>;
    async fn release_all(&self) -> anyhow::Result<()>;
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CdpTarget {
    #[serde(rename = "type")]
    target_type: String,
    web_socket_debugger_url: String,
}

/// 通过 Browser Node 内部 CDP 连接执行真实输入。
///
/// 所有输入都按 sequence 串行，旧 sequence 被拒绝，重复 sequence 幂等忽略。
#[derive(Clone)]
pub struct CdpDesktopInput {
    websocket_url: Arc<RwLock<String>>,
    ledger: Arc<Mutex<InputLedger>>,
    last_activity: Arc<Mutex<Instant>>,
}

impl CdpDesktopInput {
    pub async fn connect(cdp_endpoint: &str) -> anyhow::Result<Self> {
        anyhow::ensure!(
            cdp_endpoint.starts_with("http://127.0.0.1:")
                || cdp_endpoint.starts_with("http://localhost:"),
            "CDP endpoint must use the Browser Node loopback interface"
        );
        let endpoint = cdp_endpoint.trim_end_matches('/');
        let response = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(1))
            .timeout(Duration::from_secs(2))
            .no_proxy()
            .build()?
            .get(format!("{endpoint}/json/list"))
            .send()
            .await?
            .error_for_status()?;
        let targets: Vec<CdpTarget> = response.json().await?;
        let websocket_url = targets
            .into_iter()
            .find(|target| target.target_type == "page")
            .map(|target| target.web_socket_debugger_url)
            .ok_or_else(|| anyhow::anyhow!("CDP has no page target"))?;
        Self::require_loopback_websocket(&websocket_url)?;
        Ok(Self {
            websocket_url: Arc::new(RwLock::new(websocket_url)),
            ledger: Arc::new(Mutex::new(InputLedger::default())),
            last_activity: Arc::new(Mutex::new(Instant::now())),
        })
    }

    /// Rebind structured Agent input to the Browser-level active Page Target.
    ///
    /// Pending modifiers/buttons are released on the previous Page before the switch so a tab
    /// transition cannot strand input state in either renderer. The monotonically increasing
    /// sequence ledger is intentionally retained across tabs.
    pub async fn rebind(&self, websocket_url: &str) -> anyhow::Result<()> {
        Self::require_loopback_websocket(websocket_url)?;
        if self.websocket_url.read().await.as_str() == websocket_url {
            return Ok(());
        }
        self.release_all().await?;
        *self.websocket_url.write().await = websocket_url.to_owned();
        Ok(())
    }

    fn require_loopback_websocket(websocket_url: &str) -> anyhow::Result<()> {
        let url = reqwest::Url::parse(websocket_url)?;
        anyhow::ensure!(url.scheme() == "ws", "CDP websocket must use ws");
        let host = url
            .host_str()
            .ok_or_else(|| anyhow::anyhow!("CDP websocket host is unavailable"))?;
        let loopback = host.eq_ignore_ascii_case("localhost")
            || host
                .parse::<std::net::IpAddr>()
                .map(|address| address.is_loopback())
                .unwrap_or(false);
        anyhow::ensure!(loopback, "CDP websocket must use the Browser Node loopback");
        Ok(())
    }

    pub async fn ledger_snapshot(&self) -> InputLedger {
        let ledger = self.ledger.lock().await;
        InputLedger {
            pressed_keys: ledger.pressed_keys.clone(),
            pressed_buttons: ledger.pressed_buttons.clone(),
            active_drag: ledger.active_drag,
            last_sequence: ledger.last_sequence,
            pointer_x: ledger.pointer_x,
            pointer_y: ledger.pointer_y,
        }
    }

    /// Dispatch one atomic Chromium mouse click with an explicit click count.
    ///
    /// Ordinary DesktopInput down/up calls retain clickCount=1 for VNC compatibility. Structured
    /// Agent actions use this bounded helper for real double-click and right-click semantics while
    /// guaranteeing that no pressed button remains in the ledger.
    pub async fn mouse_click(
        &self,
        button: u8,
        click_count: u8,
        sequence: u64,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(matches!(click_count, 1 | 2), "click count must be 1 or 2");
        let mut ledger = self.ledger.lock().await;
        if !Self::validate_sequence(&ledger, sequence)? {
            return Ok(());
        }
        let button_name = Self::button_name(button)?;
        let button_mask = Self::button_mask(button)?;
        let pressed_buttons = Self::pressed_button_mask(&ledger)? | button_mask;
        let modifiers = Self::modifiers(&ledger);
        self.send(
            "Input.dispatchMouseEvent",
            serde_json::json!({
                "type": "mousePressed",
                "x": ledger.pointer_x,
                "y": ledger.pointer_y,
                "button": button_name,
                "buttons": pressed_buttons,
                "clickCount": click_count,
                "modifiers": modifiers
            }),
        )
        .await?;
        ledger.press_button(button);
        ledger.active_drag = true;
        let remaining_buttons = Self::pressed_button_mask(&ledger)? & !button_mask;
        self.send(
            "Input.dispatchMouseEvent",
            serde_json::json!({
                "type": "mouseReleased",
                "x": ledger.pointer_x,
                "y": ledger.pointer_y,
                "button": button_name,
                "buttons": remaining_buttons,
                "clickCount": click_count,
                "modifiers": modifiers
            }),
        )
        .await?;
        ledger.release_button(button);
        if ledger.pressed_buttons.is_empty() {
            ledger.active_drag = false;
        }
        ledger.last_sequence = sequence;
        self.mark_activity().await;
        Ok(())
    }

    fn input_key_name(key: &InputKey) -> anyhow::Result<String> {
        match key {
            InputKey::Shift => Ok("Shift".into()),
            InputKey::Control => Ok("Control".into()),
            InputKey::Alt => Ok("Alt".into()),
            InputKey::Meta => Ok("Meta".into()),
            InputKey::Enter => Ok("Enter".into()),
            InputKey::Tab => Ok("Tab".into()),
            InputKey::Escape => Ok("Escape".into()),
            InputKey::Backspace => Ok("Backspace".into()),
            InputKey::Delete => Ok("Delete".into()),
            InputKey::ArrowUp => Ok("ArrowUp".into()),
            InputKey::ArrowDown => Ok("ArrowDown".into()),
            InputKey::ArrowLeft => Ok("ArrowLeft".into()),
            InputKey::ArrowRight => Ok("ArrowRight".into()),
            InputKey::Character(value) => {
                anyhow::ensure!(
                    !value.is_empty() && value.chars().count() <= 32,
                    "character key must contain 1 to 32 characters"
                );
                Ok(value.clone())
            }
        }
    }

    fn button_name(button: u8) -> anyhow::Result<&'static str> {
        match button {
            0 => Ok("left"),
            1 => Ok("middle"),
            2 => Ok("right"),
            _ => anyhow::bail!("mouse button must be 0, 1, or 2"),
        }
    }

    fn button_mask(button: u8) -> anyhow::Result<u8> {
        match button {
            0 => Ok(1),
            1 => Ok(4),
            2 => Ok(2),
            _ => anyhow::bail!("mouse button must be 0, 1, or 2"),
        }
    }

    fn pressed_button_mask(ledger: &InputLedger) -> anyhow::Result<u8> {
        ledger
            .pressed_buttons
            .iter()
            .try_fold(0, |mask, button| Ok(mask | Self::button_mask(*button)?))
    }

    fn modifiers(ledger: &InputLedger) -> u8 {
        let mut modifiers = 0;
        if ledger.pressed_keys.contains(&InputKey::Alt) {
            modifiers |= 1;
        }
        if ledger.pressed_keys.contains(&InputKey::Control) {
            modifiers |= 2;
        }
        if ledger.pressed_keys.contains(&InputKey::Meta) {
            modifiers |= 4;
        }
        if ledger.pressed_keys.contains(&InputKey::Shift) {
            modifiers |= 8;
        }
        modifiers
    }

    async fn send(&self, method: &str, params: serde_json::Value) -> anyhow::Result<()> {
        let websocket_url = self.websocket_url.read().await.clone();
        let (mut socket, _) = timeout(
            Duration::from_secs(3),
            tokio_tungstenite::connect_async(&websocket_url),
        )
        .await
        .map_err(|_| anyhow::anyhow!("CDP input connection timed out"))??;
        socket
            .send(Message::Text(
                serde_json::json!({"id": 1, "method": method, "params": params}).to_string(),
            ))
            .await?;
        while let Some(message) = timeout(Duration::from_secs(3), socket.next())
            .await
            .map_err(|_| anyhow::anyhow!("CDP input command timed out"))?
        {
            let Message::Text(text) = message? else {
                continue;
            };
            let response: serde_json::Value = serde_json::from_str(&text)?;
            if response.get("id").and_then(serde_json::Value::as_i64) != Some(1) {
                continue;
            }
            if let Some(error) = response.get("error") {
                anyhow::bail!("CDP input command failed: {error}");
            }
            return Ok(());
        }
        anyhow::bail!("CDP websocket closed before input acknowledgement")
    }

    async fn mark_activity(&self) {
        *self.last_activity.lock().await = Instant::now();
    }

    async fn release_locked(&self, ledger: &mut InputLedger) -> anyhow::Result<()> {
        for key in ledger.pressed_keys.clone() {
            self.send(
                "Input.dispatchKeyEvent",
                serde_json::json!({
                    "type": "keyUp",
                    "key": Self::input_key_name(&key)?,
                    "modifiers": 0
                }),
            )
            .await?;
        }
        for button in ledger.pressed_buttons.clone() {
            self.send(
                "Input.dispatchMouseEvent",
                serde_json::json!({
                    "type": "mouseReleased",
                    "x": ledger.pointer_x,
                    "y": ledger.pointer_y,
                    "button": Self::button_name(button)?,
                    "buttons": 0,
                    "clickCount": 0,
                    "modifiers": 0
                }),
            )
            .await?;
        }
        ledger.release_all();
        self.mark_activity().await;
        Ok(())
    }

    /// 输入通道长时间无活动但账本仍有按下状态时，主动释放所有输入。
    pub async fn release_if_idle(&self, max_idle: Duration) -> anyhow::Result<bool> {
        let mut ledger = self.ledger.lock().await;
        if !ledger.has_any_input() || self.last_activity.lock().await.elapsed() <= max_idle {
            return Ok(false);
        }
        tracing::warn!("Input watchdog triggered, releasing all inputs");
        self.release_locked(&mut ledger).await?;
        Ok(true)
    }

    fn validate_sequence(ledger: &InputLedger, sequence: u64) -> anyhow::Result<bool> {
        anyhow::ensure!(sequence > 0, "input sequence must be positive");
        if sequence == ledger.last_sequence {
            return Ok(false);
        }
        anyhow::ensure!(
            sequence > ledger.last_sequence,
            "input sequence is older than the last applied sequence"
        );
        Ok(true)
    }
}

#[async_trait]
impl DesktopInput for CdpDesktopInput {
    async fn mouse_move(&self, x: i32, y: i32, sequence: u64) -> anyhow::Result<()> {
        let mut ledger = self.ledger.lock().await;
        if !Self::validate_sequence(&ledger, sequence)? {
            return Ok(());
        }
        self.send(
            "Input.dispatchMouseEvent",
            serde_json::json!({
                "type": "mouseMoved",
                "x": x,
                "y": y,
                "modifiers": Self::modifiers(&ledger)
            }),
        )
        .await?;
        ledger.pointer_x = x;
        ledger.pointer_y = y;
        ledger.last_sequence = sequence;
        self.mark_activity().await;
        Ok(())
    }

    async fn mouse_down(&self, button: u8, sequence: u64) -> anyhow::Result<()> {
        let mut ledger = self.ledger.lock().await;
        if !Self::validate_sequence(&ledger, sequence)? {
            return Ok(());
        }
        let buttons = Self::pressed_button_mask(&ledger)? | Self::button_mask(button)?;
        self.send(
            "Input.dispatchMouseEvent",
            serde_json::json!({
                "type": "mousePressed",
                "x": ledger.pointer_x,
                "y": ledger.pointer_y,
                "button": Self::button_name(button)?,
                "buttons": buttons,
                "clickCount": 1,
                "modifiers": Self::modifiers(&ledger)
            }),
        )
        .await?;
        ledger.press_button(button);
        ledger.active_drag = true;
        ledger.last_sequence = sequence;
        self.mark_activity().await;
        Ok(())
    }

    async fn mouse_up(&self, button: u8, sequence: u64) -> anyhow::Result<()> {
        let mut ledger = self.ledger.lock().await;
        if !Self::validate_sequence(&ledger, sequence)? {
            return Ok(());
        }
        let buttons = Self::pressed_button_mask(&ledger)? & !Self::button_mask(button)?;
        self.send(
            "Input.dispatchMouseEvent",
            serde_json::json!({
                "type": "mouseReleased",
                "x": ledger.pointer_x,
                "y": ledger.pointer_y,
                "button": Self::button_name(button)?,
                "buttons": buttons,
                "clickCount": 1,
                "modifiers": Self::modifiers(&ledger)
            }),
        )
        .await?;
        ledger.release_button(button);
        if ledger.pressed_buttons.is_empty() {
            ledger.active_drag = false;
        }
        ledger.last_sequence = sequence;
        self.mark_activity().await;
        Ok(())
    }

    async fn key_down(&self, key: InputKey, sequence: u64) -> anyhow::Result<()> {
        let mut ledger = self.ledger.lock().await;
        if !Self::validate_sequence(&ledger, sequence)? {
            return Ok(());
        }
        let key_name = Self::input_key_name(&key)?;
        let mut params = serde_json::json!({
            "type": "keyDown",
            "key": key_name.clone(),
            "modifiers": Self::modifiers(&ledger)
        });
        if matches!(key, InputKey::Character(_)) {
            params["text"] = serde_json::Value::String(key_name);
        }
        self.send("Input.dispatchKeyEvent", params).await?;
        ledger.press_key(key);
        ledger.last_sequence = sequence;
        self.mark_activity().await;
        Ok(())
    }

    async fn key_up(&self, key: InputKey, sequence: u64) -> anyhow::Result<()> {
        let mut ledger = self.ledger.lock().await;
        if !Self::validate_sequence(&ledger, sequence)? {
            return Ok(());
        }
        let key_name = Self::input_key_name(&key)?;
        self.send(
            "Input.dispatchKeyEvent",
            serde_json::json!({
                "type": "keyUp",
                "key": key_name,
                "modifiers": Self::modifiers(&ledger)
            }),
        )
        .await?;
        ledger.release_key(&key);
        ledger.last_sequence = sequence;
        self.mark_activity().await;
        Ok(())
    }

    async fn insert_text(&self, text: &str, sequence: u64) -> anyhow::Result<()> {
        anyhow::ensure!(
            !text.is_empty() && text.chars().count() <= 2000,
            "insert text must contain 1 to 2000 characters"
        );
        anyhow::ensure!(
            !text
                .chars()
                .any(|character| character.is_control() && !matches!(character, '\n' | '\t')),
            "insert text contains unsupported control characters"
        );
        let mut ledger = self.ledger.lock().await;
        if !Self::validate_sequence(&ledger, sequence)? {
            return Ok(());
        }
        self.send("Input.insertText", serde_json::json!({"text": text}))
            .await?;
        ledger.last_sequence = sequence;
        self.mark_activity().await;
        Ok(())
    }

    async fn release_all(&self) -> anyhow::Result<()> {
        let mut ledger = self.ledger.lock().await;
        self.release_locked(&mut ledger).await
    }
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
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;
    use tokio::sync::mpsc;

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

    #[test]
    fn maps_internal_mouse_buttons_to_cdp_bitmask() {
        assert_eq!(CdpDesktopInput::button_mask(0).unwrap(), 1);
        assert_eq!(CdpDesktopInput::button_mask(1).unwrap(), 4);
        assert_eq!(CdpDesktopInput::button_mask(2).unwrap(), 2);
        assert!(CdpDesktopInput::button_mask(3).is_err());
    }

    #[tokio::test]
    async fn dispatches_real_cdp_input_and_releases_pressed_keys() {
        let websocket_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let websocket_address = websocket_listener.local_addr().unwrap();
        let (sender, mut receiver) = mpsc::channel(4);
        let websocket_task = tokio::spawn(async move {
            for _ in 0..3 {
                let (stream, _) = websocket_listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                    panic!("expected CDP text command");
                };
                let request: serde_json::Value = serde_json::from_str(&request).unwrap();
                sender.send(request).await.unwrap();
                socket
                    .send(Message::Text(
                        serde_json::json!({"id": 1, "result": {}}).to_string(),
                    ))
                    .await
                    .unwrap();
            }
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            let (mut stream, _) = http_listener.accept().await.unwrap();
            let mut request = vec![0_u8; 4096];
            let count = stream.read(&mut request).await.unwrap();
            assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/list "));
            let body = serde_json::json!([{
                "type": "page",
                "webSocketDebuggerUrl": format!("ws://{websocket_address}/devtools/page/1")
            }])
            .to_string();
            stream
                .write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                        body.len(),
                        body
                    )
                    .as_bytes(),
                )
                .await
                .unwrap();
        });

        let input = CdpDesktopInput::connect(&format!("http://{http_address}"))
            .await
            .unwrap();
        input
            .key_down(InputKey::Shift, 1)
            .await
            .expect("key down should be delivered");
        input
            .key_down(InputKey::Shift, 1)
            .await
            .expect("duplicate sequence should be idempotent");
        assert!(input
            .ledger_snapshot()
            .await
            .pressed_keys
            .contains(&InputKey::Shift));
        assert!(input.release_if_idle(Duration::ZERO).await.unwrap());
        assert!(!input.release_if_idle(Duration::ZERO).await.unwrap());
        assert!(!input.ledger_snapshot().await.has_any_input());
        input
            .insert_text("public note", 2)
            .await
            .expect("text should be delivered through Input.insertText");

        let key_down = receiver.recv().await.unwrap();
        let key_up = receiver.recv().await.unwrap();
        let insert_text = receiver.recv().await.unwrap();
        assert_eq!(key_down["method"], "Input.dispatchKeyEvent");
        assert_eq!(key_down["params"]["type"], "keyDown");
        assert!(key_down["params"].get("text").is_none());
        assert_eq!(key_up["params"]["type"], "keyUp");
        assert_eq!(insert_text["method"], "Input.insertText");
        assert_eq!(insert_text["params"]["text"], "public note");
        assert!(receiver.try_recv().is_err());

        websocket_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn dispatches_atomic_double_right_click_count_without_leaking_button_state() {
        let websocket_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let websocket_address = websocket_listener.local_addr().unwrap();
        let (sender, mut receiver) = mpsc::channel(4);
        let websocket_task = tokio::spawn(async move {
            for _ in 0..4 {
                let (stream, _) = websocket_listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                    panic!("expected CDP text command");
                };
                sender
                    .send(serde_json::from_str::<serde_json::Value>(&request).unwrap())
                    .await
                    .unwrap();
                socket
                    .send(Message::Text(
                        serde_json::json!({"id": 1, "result": {}}).to_string(),
                    ))
                    .await
                    .unwrap();
            }
        });
        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            let (mut stream, _) = http_listener.accept().await.unwrap();
            let mut request = vec![0_u8; 4096];
            let count = stream.read(&mut request).await.unwrap();
            assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/list "));
            let body = serde_json::json!([{
                "type": "page",
                "webSocketDebuggerUrl": format!("ws://{websocket_address}/devtools/page/1")
            }])
            .to_string();
            stream
                .write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                        body.len(),
                        body
                    )
                    .as_bytes(),
                )
                .await
                .unwrap();
        });

        let input = CdpDesktopInput::connect(&format!("http://{http_address}"))
            .await
            .unwrap();
        input.mouse_click(0, 2, 1).await.unwrap();
        input.mouse_click(2, 1, 2).await.unwrap();

        let requests = [
            receiver.recv().await.unwrap(),
            receiver.recv().await.unwrap(),
            receiver.recv().await.unwrap(),
            receiver.recv().await.unwrap(),
        ];
        assert_eq!(requests[0]["params"]["button"], "left");
        assert_eq!(requests[0]["params"]["clickCount"], 2);
        assert_eq!(requests[1]["params"]["type"], "mouseReleased");
        assert_eq!(requests[2]["params"]["button"], "right");
        assert_eq!(requests[2]["params"]["clickCount"], 1);
        assert_eq!(requests[3]["params"]["buttons"], 0);
        assert!(!input.ledger_snapshot().await.has_any_input());

        websocket_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn rebinds_input_to_the_authoritative_active_page_and_releases_old_page_state() {
        let old_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let old_address = old_listener.local_addr().unwrap();
        let (old_sender, mut old_receiver) = mpsc::channel(2);
        let old_task = tokio::spawn(async move {
            for _ in 0..2 {
                let (stream, _) = old_listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                    panic!("expected old-page CDP command");
                };
                let request: serde_json::Value = serde_json::from_str(&request).unwrap();
                old_sender.send(request.clone()).await.unwrap();
                socket
                    .send(Message::Text(
                        serde_json::json!({"id": request["id"], "result": {}}).to_string(),
                    ))
                    .await
                    .unwrap();
            }
        });
        let new_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let new_address = new_listener.local_addr().unwrap();
        let (new_sender, mut new_receiver) = mpsc::channel(1);
        let new_task = tokio::spawn(async move {
            let (stream, _) = new_listener.accept().await.unwrap();
            let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
            let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                panic!("expected new-page CDP command");
            };
            let request: serde_json::Value = serde_json::from_str(&request).unwrap();
            new_sender.send(request.clone()).await.unwrap();
            socket
                .send(Message::Text(
                    serde_json::json!({"id": request["id"], "result": {}}).to_string(),
                ))
                .await
                .unwrap();
        });

        let input = CdpDesktopInput {
            websocket_url: Arc::new(RwLock::new(format!("ws://{old_address}/devtools/page/old"))),
            ledger: Arc::new(Mutex::new(InputLedger::default())),
            last_activity: Arc::new(Mutex::new(Instant::now())),
        };
        input.key_down(InputKey::Shift, 1).await.unwrap();
        input
            .rebind(&format!("ws://{new_address}/devtools/page/new"))
            .await
            .unwrap();
        input.insert_text("next tab", 2).await.unwrap();
        assert!(input
            .rebind("ws://example.com/devtools/page/remote")
            .await
            .is_err());

        assert_eq!(
            old_receiver.recv().await.unwrap()["params"]["type"],
            "keyDown"
        );
        assert_eq!(
            old_receiver.recv().await.unwrap()["params"]["type"],
            "keyUp"
        );
        assert_eq!(
            new_receiver.recv().await.unwrap()["params"]["text"],
            "next tab"
        );
        assert!(!input.ledger_snapshot().await.has_any_input());
        old_task.await.unwrap();
        new_task.await.unwrap();
    }

    #[tokio::test]
    async fn rejects_old_input_sequence() {
        let ledger = InputLedger {
            last_sequence: 10,
            ..InputLedger::default()
        };
        assert!(!CdpDesktopInput::validate_sequence(&ledger, 10).unwrap());
        assert!(CdpDesktopInput::validate_sequence(&ledger, 9).is_err());
        assert!(CdpDesktopInput::validate_sequence(&ledger, 11).unwrap());
    }
}
