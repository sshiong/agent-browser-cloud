use anyhow::{ensure, Result};
use recording_privacy_scanner::{ScannerRuntime, MAX_INPUT_BYTES, SCAN_VERSION};
use serde_json::{json, Value};
use std::io::{self, BufRead, Write};

fn run() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() == 2 && args[1] == "--self-test" {
        let runtime = ScannerRuntime::from_env()?;
        runtime.self_test()?;
        println!(
            "{}",
            serde_json::to_string(&json!({ "ready": true, "scanVersion": SCAN_VERSION }))?
        );
        return Ok(());
    }
    ensure!(args.len() == 1, "ARGUMENT_INVALID");

    let runtime = ScannerRuntime::from_env()?;
    let stdin = io::stdin();
    let mut stdout = io::stdout().lock();
    for line in stdin.lock().lines() {
        let doc = line?;
        ensure!(doc.len() < MAX_INPUT_BYTES * 2, "REQUEST_TOO_LARGE");
        if doc.trim().is_empty() {
            continue;
        }
        let req: Value = serde_json::from_str(&doc)?;
        let obj = req
            .as_object()
            .ok_or_else(|| anyhow::anyhow!("REQUEST_INVALID"))?;
        ensure!(obj.len() == 1, "REQUEST_INVALID");
        let b64 = obj
            .get("imageBase64")
            .and_then(Value::as_str)
            .ok_or_else(|| anyhow::anyhow!("REQUEST_INVALID"))?;
        let resp = runtime.scan_base64(b64)?;
        writeln!(stdout, "{}", serde_json::to_string(&resp)?)?;
        stdout.flush()?;
    }
    Ok(())
}

fn main() {
    if let Err(err) = run() {
        let _ = writeln!(io::stderr(), "{}", json!({ "error": err.to_string() }));
        std::process::exit(2);
    }
}
