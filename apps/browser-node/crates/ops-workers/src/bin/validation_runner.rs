use ops_workers::validation_runner::{execute, RunnerError, ValidationRunnerArgs};
use serde_json::{json, Value};
use std::io::Read;
use std::path::PathBuf;

fn parse_args() -> Result<ValidationRunnerArgs, String> {
    let mut args = std::env::args().skip(1);
    let mut browser: Option<String> = None;
    let mut browser_args: Vec<String> = Vec::new();
    let mut suite_catalog: Option<PathBuf> = None;
    let mut case_timeout_seconds: u64 = 30;
    let mut allow_http = false;

    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--browser" => browser = args.next(),
            "--browser-arg" => {
                let val = args
                    .next()
                    .ok_or_else(|| "--browser-arg requires a value".to_owned())?;
                browser_args.push(val);
            }
            "--suite-catalog" => suite_catalog = args.next().map(PathBuf::from),
            "--case-timeout-seconds" => {
                let raw = args
                    .next()
                    .ok_or_else(|| "--case-timeout-seconds requires a value".to_owned())?;
                case_timeout_seconds = raw
                    .parse::<u64>()
                    .map_err(|_| "--case-timeout-seconds must be an integer".to_owned())?;
            }
            "--allow-http" => allow_http = true,
            other => return Err(format!("unknown argument: {other}")),
        }
    }

    Ok(ValidationRunnerArgs {
        browser: browser.ok_or_else(|| "--browser is required".to_owned())?,
        browser_args,
        suite_catalog: suite_catalog.ok_or_else(|| "--suite-catalog is required".to_owned())?,
        case_timeout_seconds,
        allow_http,
    })
}

fn main() -> std::process::ExitCode {
    let parsed = match parse_args() {
        Ok(a) => a,
        Err(msg) => {
            eprintln!("{{\"error\":\"{msg}\"}}");
            return std::process::ExitCode::from(2);
        }
    };

    let mut stdin_buf = String::new();
    if std::io::stdin().read_to_string(&mut stdin_buf).is_err() {
        eprintln!("{{\"error\":\"RUNNER_INTERNAL_ERROR\"}}");
        return std::process::ExitCode::from(2);
    }

    let validation: Value = match serde_json::from_str::<Value>(&stdin_buf) {
        Ok(v) if v.is_object() => v,
        Ok(_) => {
            eprintln!("{{\"error\":\"VALIDATION_INPUT_INVALID\"}}");
            return std::process::ExitCode::from(2);
        }
        Err(_) => {
            eprintln!("{{\"error\":\"RUNNER_INTERNAL_ERROR\"}}");
            return std::process::ExitCode::from(2);
        }
    };

    match execute(&parsed, &validation) {
        Ok(result) => {
            println!("{}", serde_json::to_string(&result).unwrap_or_default());
            std::process::ExitCode::SUCCESS
        }
        Err(RunnerError(code)) => {
            eprintln!("{}", json!({ "error": code }));
            std::process::ExitCode::from(2)
        }
    }
}
