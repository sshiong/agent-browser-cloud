use ops_workers::gameday_runner::{emit_result, run_gameday, GameDayRunnerArgs};
use serde_json::json;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

fn parse_args() -> Result<GameDayRunnerArgs, String> {
    let mut args = std::env::args().skip(1);
    let mut catalog_file: Option<PathBuf> = None;
    let mut controller_token_file: Option<PathBuf> = None;
    let mut ca_file: Option<PathBuf> = None;
    let mut game_day_id: Option<String> = None;
    let mut scenario_code: Option<String> = None;
    let mut environment: Option<String> = None;
    let mut source_region: Option<String> = None;
    let mut target_region: Option<String> = None;
    let mut maximum_duration_seconds: Option<u64> = None;
    let mut recovery_only = false;

    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--catalog-file" => catalog_file = args.next().map(PathBuf::from),
            "--controller-token-file" => controller_token_file = args.next().map(PathBuf::from),
            "--ca-file" => ca_file = args.next().map(PathBuf::from),
            "--game-day-id" => game_day_id = args.next(),
            "--scenario-code" => scenario_code = args.next(),
            "--environment" => environment = args.next(),
            "--source-region" => source_region = args.next(),
            "--target-region" => target_region = args.next(),
            "--maximum-duration-seconds" => {
                let raw = args
                    .next()
                    .ok_or_else(|| "--maximum-duration-seconds requires a value".to_owned())?;
                maximum_duration_seconds = Some(
                    raw.parse::<u64>()
                        .map_err(|_| "--maximum-duration-seconds must be an integer".to_owned())?,
                );
            }
            "--recovery-only" => recovery_only = true,
            other => return Err(format!("unknown argument: {other}")),
        }
    }

    Ok(GameDayRunnerArgs {
        catalog_file: catalog_file.ok_or_else(|| "--catalog-file is required".to_owned())?,
        controller_token_file: controller_token_file
            .ok_or_else(|| "--controller-token-file is required".to_owned())?,
        ca_file,
        game_day_id: game_day_id.ok_or_else(|| "--game-day-id is required".to_owned())?,
        scenario_code: scenario_code.ok_or_else(|| "--scenario-code is required".to_owned())?,
        environment: environment.ok_or_else(|| "--environment is required".to_owned())?,
        source_region: source_region.ok_or_else(|| "--source-region is required".to_owned())?,
        target_region: target_region.ok_or_else(|| "--target-region is required".to_owned())?,
        maximum_duration_seconds: maximum_duration_seconds
            .ok_or_else(|| "--maximum-duration-seconds is required".to_owned())?,
        recovery_only,
    })
}

#[tokio::main]
async fn main() -> std::process::ExitCode {
    let parsed = match parse_args() {
        Ok(a) => a,
        Err(msg) => {
            eprintln!("gameday runner argument error: {msg}");
            return std::process::ExitCode::from(2);
        }
    };

    let cancelled = Arc::new(AtomicBool::new(false));
    let c_sigint = cancelled.clone();
    tokio::spawn(async move {
        let _ = tokio::signal::ctrl_c().await;
        c_sigint.store(true, Ordering::SeqCst);
    });
    #[cfg(unix)]
    {
        let c_sigterm = cancelled.clone();
        if let Ok(mut sigterm) =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            tokio::spawn(async move {
                sigterm.recv().await;
                c_sigterm.store(true, Ordering::SeqCst);
            });
        }
    }

    match run_gameday(&parsed, cancelled.clone()).await {
        Ok(result) => {
            emit_result(&result);
            std::process::ExitCode::SUCCESS
        }
        Err(_) => {
            emit_result(&json!({
                "gameDayId": parsed.game_day_id,
                "scenarioCode": parsed.scenario_code,
                "environment": parsed.environment,
                "recoveryConfirmed": false,
                "aborted": cancelled.load(Ordering::SeqCst),
                "failureCode": "GAMEDAY_RUNNER_RUNTIMEERROR",
            }));
            std::process::ExitCode::from(1)
        }
    }
}
