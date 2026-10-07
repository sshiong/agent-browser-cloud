//! Owned-page experiments for the next property observer. This is not a production observer.
//! No browser input values, CDP event bodies, URLs or exception messages are emitted.

use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::path::PathBuf;
use std::process::Stdio;
use tokio::time::{timeout_at, Duration, Instant};
use tokio_tungstenite::tungstenite::Message;

type Socket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

struct Probe {
    socket: Socket,
    sequence: u64,
}

impl Probe {
    async fn connect(endpoint: &str) -> anyhow::Result<Self> {
        let (socket, _) = tokio::time::timeout(
            Duration::from_secs(3),
            tokio_tungstenite::connect_async(endpoint),
        )
        .await
        .map_err(|_| anyhow::anyhow!("OWNED_PROBE_CONNECT_TIMEOUT"))?
        .map_err(|_| anyhow::anyhow!("OWNED_PROBE_CONNECT_FAILED"))?;
        Ok(Self {
            socket,
            sequence: 0,
        })
    }

    async fn command(&mut self, method: &str, params: Value) -> anyhow::Result<Value> {
        self.sequence += 1;
        let id = self.sequence;
        let deadline = Instant::now() + Duration::from_secs(3);
        timeout_at(
            deadline,
            self.socket.send(Message::Text(
                json!({"id":id,"method":method,"params":params}).to_string(),
            )),
        )
        .await
        .map_err(|_| anyhow::anyhow!("OWNED_PROBE_SEND_TIMEOUT"))?
        .map_err(|_| anyhow::anyhow!("OWNED_PROBE_SEND_FAILED"))?;
        for _ in 0..4096 {
            let message = timeout_at(deadline, self.socket.next())
                .await
                .map_err(|_| anyhow::anyhow!("OWNED_PROBE_RESPONSE_TIMEOUT"))?
                .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_CONNECTION_CLOSED"))?
                .map_err(|_| anyhow::anyhow!("OWNED_PROBE_READ_FAILED"))?;
            let Message::Text(text) = message else {
                continue;
            };
            anyhow::ensure!(text.len() <= 131_072, "OWNED_PROBE_MESSAGE_BUDGET");
            let reply: Value = serde_json::from_str(&text)
                .map_err(|_| anyhow::anyhow!("OWNED_PROBE_INVALID_JSON"))?;
            // Even on a test failure, never resume a pause merely because a breakpoint ID matches.
            anyhow::ensure!(
                reply["method"] != "Debugger.paused",
                "OWNED_PROBE_UNEXPECTED_PAUSE"
            );
            if reply["id"] != id {
                continue;
            }
            anyhow::ensure!(reply.get("error").is_none(), "OWNED_PROBE_COMMAND_REJECTED");
            let result = reply
                .get("result")
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_MISSING_RESULT"))?;
            anyhow::ensure!(
                result.get("exceptionDetails").is_none(),
                "OWNED_PROBE_SCRIPT_REJECTED"
            );
            return Ok(result);
        }
        anyhow::bail!("OWNED_PROBE_MESSAGE_BUDGET")
    }

    async fn evaluate(&mut self, expression: &str) -> anyhow::Result<Value> {
        let result = self
            .command(
                "Runtime.evaluate",
                json!({"expression":expression,"returnByValue":true}),
            )
            .await?;
        result
            .pointer("/result/value")
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_MISSING_VALUE"))
    }

    async fn remote(&mut self, expression: &str, context: Option<i64>) -> anyhow::Result<Value> {
        let mut params = json!({"expression":expression,"returnByValue":false});
        if let Some(context) = context {
            params["contextId"] = json!(context);
        }
        let result = self.command("Runtime.evaluate", params).await?;
        result
            .get("result")
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_MISSING_REMOTE"))
    }

    async fn native_candidate(&mut self, remote: &Value) -> anyhow::Result<bool> {
        if remote["type"] != "function"
            || remote["subtype"] == "proxy"
            || remote["description"] != "function set checked() { [native code] }"
        {
            return Ok(false);
        }
        let object = object_id(remote)?;
        let properties = self
            .command(
                "Runtime.getProperties",
                json!({
                    "objectId":object,"ownProperties":true,"generatePreview":false
                }),
            )
            .await?;
        let internal = properties["internalProperties"]
            .as_array()
            .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_MISSING_FUNCTION_METADATA"))?;
        Ok(!internal.iter().any(|property| {
            matches!(
                property["name"].as_str(),
                Some("[[FunctionLocation]]" | "[[BoundTargetFunction]]" | "[[Target]]")
            )
        }))
    }

    async fn counts(&mut self, ledger: &str) -> anyhow::Result<(u64, u64)> {
        let result = self
            .command(
                "Runtime.callFunctionOn",
                json!({
                    "objectId":ledger,"functionDeclaration":"function(){return this()}",
                    "returnByValue":true
                }),
            )
            .await?;
        let values = result
            .pointer("/result/value")
            .and_then(Value::as_array)
            .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_MISSING_COUNTS"))?;
        anyhow::ensure!(values.len() == 2, "OWNED_PROBE_INVALID_COUNTS");
        Ok((
            values[0]
                .as_u64()
                .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_INVALID_COUNT"))?,
            values[1]
                .as_u64()
                .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_INVALID_COUNT"))?,
        ))
    }
}

fn object_id(remote: &Value) -> anyhow::Result<&str> {
    remote["objectId"]
        .as_str()
        .filter(|value| !value.is_empty())
        .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_MISSING_OBJECT"))
}

struct OwnedChrome {
    child: tokio::process::Child,
    profile: PathBuf,
}

impl Drop for OwnedChrome {
    fn drop(&mut self) {
        // Only this test's child is stopped. An unsuccessful run keeps its private directory.
        let _ = self.child.start_kill();
    }
}

impl OwnedChrome {
    async fn start() -> anyhow::Result<(Self, String)> {
        let binary = std::env::var_os("REAL_CHROMIUM_PATH")
            .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_CHROMIUM_PATH_REQUIRED"))?;
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)?
            .as_nanos();
        let profile = std::env::temp_dir().join(format!("browsercloud-native-probe-{nonce}"));
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            std::fs::DirBuilder::new().mode(0o700).create(&profile)?;
        }
        #[cfg(not(unix))]
        std::fs::create_dir(&profile)?;
        let child = tokio::process::Command::new(binary)
            .args([
                "--headless=new",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-networking",
                "--no-proxy-server",
                "--remote-debugging-address=127.0.0.1",
                "--remote-debugging-port=0",
            ])
            .arg(format!("--user-data-dir={}", profile.display()))
            .arg("about:blank")
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .kill_on_drop(true)
            .spawn()
            .map_err(|_| anyhow::anyhow!("OWNED_PROBE_CHROMIUM_SPAWN_FAILED"))?;
        let mut chrome = Self { child, profile };
        let deadline = Instant::now() + Duration::from_secs(10);
        let port = loop {
            if let Ok(file) =
                tokio::fs::read_to_string(chrome.profile.join("DevToolsActivePort")).await
            {
                if let Some(port) = file
                    .lines()
                    .next()
                    .and_then(|line| line.parse::<u16>().ok())
                    .filter(|port| *port != 0)
                {
                    break port;
                }
            }
            anyhow::ensure!(
                chrome.child.try_wait()?.is_none(),
                "OWNED_PROBE_CHROMIUM_EXITED"
            );
            anyhow::ensure!(
                Instant::now() < deadline,
                "OWNED_PROBE_CHROMIUM_STARTUP_TIMEOUT"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        };
        let client = reqwest::Client::builder()
            .no_proxy()
            .timeout(Duration::from_secs(3))
            .build()?;
        let version: Value = client
            .get(format!("http://127.0.0.1:{port}/json/version"))
            .send()
            .await
            .map_err(|_| anyhow::anyhow!("OWNED_PROBE_DISCOVERY_FAILED"))?
            .json()
            .await
            .map_err(|_| anyhow::anyhow!("OWNED_PROBE_DISCOVERY_INVALID"))?;
        let endpoint = version["webSocketDebuggerUrl"]
            .as_str()
            .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_BROWSER_ENDPOINT_MISSING"))?;
        let mut browser = Probe::connect(endpoint).await?;
        let target = browser
            .command("Target.createTarget", json!({"url":"about:blank"}))
            .await?;
        drop(browser);
        let pages: Vec<Value> = client
            .get(format!("http://127.0.0.1:{port}/json/list"))
            .send()
            .await
            .map_err(|_| anyhow::anyhow!("OWNED_PROBE_DISCOVERY_FAILED"))?
            .json()
            .await
            .map_err(|_| anyhow::anyhow!("OWNED_PROBE_DISCOVERY_INVALID"))?;
        let endpoint = pages
            .iter()
            .find(|page| page["id"] == target["targetId"])
            .and_then(|page| page["webSocketDebuggerUrl"].as_str())
            .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_PAGE_ENDPOINT_MISSING"))?
            .to_owned();
        Ok((chrome, endpoint))
    }

    async fn finish(mut self) -> anyhow::Result<()> {
        self.child.start_kill()?;
        tokio::time::timeout(Duration::from_secs(5), self.child.wait())
            .await
            .map_err(|_| anyhow::anyhow!("OWNED_PROBE_CHROMIUM_STOP_TIMEOUT"))??;
        tokio::fs::remove_dir_all(&self.profile).await?;
        Ok(())
    }
}

#[tokio::test]
#[ignore = "requires REAL_CHROMIUM_PATH; validates a candidate, not production coverage"]
async fn real_chromium_native_property_candidate_counts_aba_without_pausing() {
    async fn run() -> anyhow::Result<()> {
        let (chrome, endpoint) = OwnedChrome::start().await?;
        let mut probe = Probe::connect(&endpoint).await?;
        probe.evaluate("document.body.innerHTML='<input id=owned-first type=checkbox><input id=owned-second type=checkbox>';true").await?;
        let frame = probe.command("Page.getFrameTree", json!({})).await?;
        let world = probe
            .command(
                "Page.createIsolatedWorld",
                json!({
                    "frameId":frame["frameTree"]["frame"]["id"],"worldName":"owned-native-probe",
                    "grantUniveralAccess":false
                }),
            )
            .await?;
        let context = world["executionContextId"]
            .as_i64()
            .ok_or_else(|| anyhow::anyhow!("OWNED_PROBE_CONTEXT_MISSING"))?;
        probe.command("Runtime.evaluate", json!({"contextId":context,"returnByValue":true,
            "expression":"globalThis.ownedMutations=0;globalThis.ownedObserver=new MutationObserver(records=>ownedMutations+=records.length);ownedObserver.observe(document,{subtree:true,attributes:true,childList:true,characterData:true});true"})).await?;
        let toggle = "(()=>{const e=document.getElementById('owned-first');e.checked=true;const changed=e.checked;e.checked=false;return changed&&!e.checked})()";
        anyhow::ensure!(
            probe.evaluate(toggle).await? == true,
            "OWNED_PROBE_TOGGLE_FAILED"
        );
        let mutation_count = probe
            .command(
                "Runtime.evaluate",
                json!({"contextId":context,
            "expression":"ownedMutations+ownedObserver.takeRecords().length","returnByValue":true}),
            )
            .await?;
        anyhow::ensure!(
            mutation_count.pointer("/result/value") == Some(&json!(0)),
            "OWNED_PROBE_MUTATION_BASELINE_CHANGED"
        );

        probe.command("Debugger.enable", json!({})).await?;
        let setter = probe
            .remote(
                "Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'checked').set",
                None,
            )
            .await?;
        anyhow::ensure!(
            probe.native_candidate(&setter).await?,
            "OWNED_PROBE_NATIVE_METADATA_CHANGED"
        );
        for expression in [
            "(()=>{const fn=function checked(){};fn.toString=()=> 'function set checked() { [native code] }';return fn})()",
            "Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'checked').set.bind(null)",
            "new Proxy(Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'checked').set,{})",
        ] {
            let remote = probe.remote(expression, None).await?;
            anyhow::ensure!(!probe.native_candidate(&remote).await?, "OWNED_PROBE_FALSE_NATIVE_CANDIDATE");
        }
        // Installing a breakpoint in an isolated realm does not cover main-realm setter calls.
        let isolated = probe
            .remote(
                "Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'checked').set",
                Some(context),
            )
            .await?;
        let isolated_bp = probe
            .command(
                "Debugger.setBreakpointOnFunctionCall",
                json!({"objectId":object_id(&isolated)?}),
            )
            .await?;
        anyhow::ensure!(
            probe.evaluate(toggle).await? == true,
            "OWNED_PROBE_ISOLATED_BASELINE_FAILED"
        );
        probe
            .command(
                "Debugger.removeBreakpoint",
                json!({"breakpointId":isolated_bp["breakpointId"]}),
            )
            .await?;

        // This controlled fixture uses its own intrinsics. Production must validate and capture
        // those references before creating a ledger in an untrusted main world.
        let ledger = probe.remote("(()=>{'use strict';const counts=[0,0];for(let index=0;index<2;index++){const node=document.getElementById(index?'owned-second':'owned-first');Object.defineProperty(node,'__ownedAppendOnlyLedger',{value:()=>{counts[index]++},writable:false,configurable:false});}return ()=>[counts[0],counts[1]]})()", None).await?;
        let ledger = object_id(&ledger)?.to_owned();
        let breakpoint = probe.command("Debugger.setBreakpointOnFunctionCall", json!({
            "objectId":object_id(&setter)?,"condition":"(this.__ownedAppendOnlyLedger?.(),false)"
        })).await?;
        let mut peer = Probe::connect(&endpoint).await?;
        peer.command("Debugger.enable", json!({})).await?;
        for expression in [
            toggle,
            "(()=>{const frame=document.createElement('iframe');document.body.appendChild(frame);const setter=Object.getOwnPropertyDescriptor(frame.contentWindow.HTMLInputElement.prototype,'checked').set;const node=document.getElementById('owned-first');setter.call(node,true);const changed=node.checked;setter.call(node,false);frame.remove();return changed&&!node.checked})()",
            "(()=>{const frame=document.createElement('iframe');document.body.appendChild(frame);const setter=Object.getOwnPropertyDescriptor(frame.contentWindow.HTMLInputElement.prototype,'checked').set;const node=frame.contentWindow.parent.document.getElementById('owned-first');setter.call(node,true);const changed=node.checked;setter.call(node,false);frame.remove();return changed&&!node.checked})()",
        ] {
            let before = probe.counts(&ledger).await?;
            anyhow::ensure!(probe.evaluate(expression).await? == true, "OWNED_PROBE_REAL_TOGGLE_FAILED");
            anyhow::ensure!(probe.counts(&ledger).await? == (before.0+2,before.1), "OWNED_PROBE_SCOPED_HISTORY_MISSING");
        }
        let before = probe.counts(&ledger).await?;
        probe.evaluate("document.getElementById('owned-second').checked=true;document.getElementById('owned-second').checked=false;true").await?;
        anyhow::ensure!(
            probe.counts(&ledger).await? == (before.0, before.1 + 2),
            "OWNED_PROBE_UNRELATED_SCOPE_CHANGED"
        );
        anyhow::ensure!(probe.evaluate("(()=>{'use strict';try{document.getElementById('owned-first').__ownedAppendOnlyLedger=()=>{};return false}catch{return true}})()").await? == true, "OWNED_PROBE_APPEND_CALLBACK_REPLACED");
        let before = probe.counts(&ledger).await?;
        probe
            .evaluate(
                "document.getElementById('owned-first').__ownedAppendOnlyLedger('reset',0);true",
            )
            .await?;
        anyhow::ensure!(
            probe.counts(&ledger).await? == (before.0 + 1, before.1),
            "OWNED_PROBE_PAGE_RESET_LEDGER"
        );

        // Different non-pausing conditions must remain independent even when CDP uses the
        // same breakpoint ID on two clients. Removing the peer must not remove this observer.
        probe
            .evaluate("globalThis.__ownedPeerCounter=0;true")
            .await?;
        let peer_setter = peer
            .remote(
                "Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'checked').set",
                None,
            )
            .await?;
        let peer_bp = peer
            .command(
                "Debugger.setBreakpointOnFunctionCall",
                json!({"objectId":object_id(&peer_setter)?,
                    "condition":"(++globalThis.__ownedPeerCounter,false)"}),
            )
            .await?;
        let before = probe.counts(&ledger).await?;
        anyhow::ensure!(
            probe.evaluate(toggle).await? == true
                && probe.counts(&ledger).await? == (before.0 + 2, before.1)
                && probe.evaluate("__ownedPeerCounter").await? == 2,
            "OWNED_PROBE_CLIENT_CONDITIONS_INTERFERED"
        );
        peer.command(
            "Debugger.removeBreakpoint",
            json!({"breakpointId":peer_bp["breakpointId"]}),
        )
        .await?;
        let before = probe.counts(&ledger).await?;
        anyhow::ensure!(
            probe.evaluate(toggle).await? == true
                && probe.counts(&ledger).await? == (before.0 + 2, before.1),
            "OWNED_PROBE_PEER_REMOVAL_LOST_HISTORY"
        );

        probe
            .command(
                "Debugger.removeBreakpoint",
                json!({"breakpointId":breakpoint["breakpointId"]}),
            )
            .await?;
        peer.command("Debugger.disable", json!({})).await?;
        probe.command("Debugger.disable", json!({})).await?;
        drop(peer);
        drop(probe);
        chrome.finish().await
    }
    // Errors are fixed classifications. Never format CDP replies, browser stderr or URLs.
    if let Err(error) = run().await {
        let class = error.to_string();
        if class.starts_with("OWNED_PROBE_")
            && class
                .bytes()
                .all(|byte| byte.is_ascii_uppercase() || byte == b'_')
        {
            panic!("{class}");
        }
        panic!("OWNED_PROBE_LOCAL_SETUP_FAILED");
    }
}
