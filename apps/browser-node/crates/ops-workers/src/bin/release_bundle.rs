use ops_workers::release_bundle::{render_bundle, verify_bundle, RenderArgs};
use std::path::PathBuf;

fn run() -> anyhow::Result<()> {
    let mut args = std::env::args().skip(1);
    let cmd = args
        .next()
        .ok_or_else(|| anyhow::anyhow!("expected subcommand: render | verify"))?;
    match cmd.as_str() {
        "render" => {
            let mut source_commit: Option<String> = None;
            let mut output: Option<PathBuf> = None;
            let mut base_dir = PathBuf::from("deploy/kubernetes/base");
            let mut images = Vec::new();
            let mut evidence = Vec::new();
            while let Some(arg) = args.next() {
                match arg.as_str() {
                    "--source-commit" => source_commit = args.next(),
                    "--output" => output = args.next().map(PathBuf::from),
                    "--base-dir" => {
                        base_dir = PathBuf::from(
                            args.next()
                                .ok_or_else(|| anyhow::anyhow!("--base-dir requires a value"))?,
                        );
                    }
                    "--image" => {
                        images.push(
                            args.next()
                                .ok_or_else(|| anyhow::anyhow!("--image requires a value"))?,
                        );
                    }
                    "--evidence" => {
                        evidence.push(
                            args.next()
                                .ok_or_else(|| anyhow::anyhow!("--evidence requires a value"))?,
                        );
                    }
                    other => anyhow::bail!("unknown argument: {other}"),
                }
            }
            render_bundle(&RenderArgs {
                source_commit: source_commit
                    .ok_or_else(|| anyhow::anyhow!("--source-commit is required"))?,
                output: output.ok_or_else(|| anyhow::anyhow!("--output is required"))?,
                base_dir,
                images,
                evidence,
            })
        }
        "verify" => {
            let mut bundle: Option<PathBuf> = None;
            while let Some(arg) = args.next() {
                match arg.as_str() {
                    "--bundle" => bundle = args.next().map(PathBuf::from),
                    other => anyhow::bail!("unknown argument: {other}"),
                }
            }
            let path = bundle.ok_or_else(|| anyhow::anyhow!("--bundle is required"))?;
            verify_bundle(&path)
        }
        other => anyhow::bail!("unknown subcommand: {other}"),
    }
}

fn main() -> std::process::ExitCode {
    match run() {
        Ok(()) => std::process::ExitCode::SUCCESS,
        Err(err) => {
            eprintln!("release bundle failed: {err}");
            std::process::ExitCode::from(1)
        }
    }
}
