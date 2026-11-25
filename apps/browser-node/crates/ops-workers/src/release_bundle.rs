//! Pure-Rust digest-locked production deployment release bundle renderer and verifier.
//!
//! 100% CLI and schemaVersion-3 byte-compatible drop-in replacement for
//! `tools/supply-chain/release_bundle.py`.

use anyhow::{bail, ensure, Result};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashMap};
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

pub const SCHEMA_VERSION: i64 = 3;
pub const SPDX_MEDIA_TYPE: &str = "application/spdx+json";

pub const COMPONENTS: &[(&str, &str)] = &[
    (
        "control-plane",
        "ghcr.io/sshiong/agent-browser-cloud-control-plane",
    ),
    (
        "browser-node",
        "ghcr.io/sshiong/agent-browser-cloud-browser-node",
    ),
    (
        "web-console",
        "ghcr.io/sshiong/agent-browser-cloud-web-console",
    ),
    ("operator", "ghcr.io/sshiong/agent-browser-cloud-operator"),
    (
        "application-adapter",
        "ghcr.io/sshiong/agent-browser-cloud-application-adapter",
    ),
    (
        "validation-worker",
        "ghcr.io/sshiong/agent-browser-cloud-validation-worker",
    ),
    (
        "gameday-worker",
        "ghcr.io/sshiong/agent-browser-cloud-gameday-worker",
    ),
    (
        "agent-worker",
        "ghcr.io/sshiong/agent-browser-cloud-agent-worker",
    ),
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImageMeta {
    pub reference: String,
    pub repository: String,
    pub digest: String,
}

#[derive(Debug, Clone)]
pub struct RenderArgs {
    pub source_commit: String,
    pub output: PathBuf,
    pub base_dir: PathBuf,
    pub images: Vec<String>,
    pub evidence: Vec<String>,
}

fn is_lower_hex(s: &str, expected_len: usize) -> bool {
    s.len() == expected_len && s.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

fn is_valid_repository(repo: &str) -> bool {
    let Some((registry, path)) = repo.split_once('/') else {
        return false;
    };
    if registry.is_empty() || path.is_empty() {
        return false;
    }
    let reg_ok = match registry.split_once(':') {
        Some((host, port)) => {
            !host.is_empty()
                && !port.is_empty()
                && host
                    .bytes()
                    .all(|b| matches!(b, b'a'..=b'z' | b'0'..=b'9' | b'.' | b'-'))
                && port.bytes().all(|b| b.is_ascii_digit())
        }
        None => registry
            .bytes()
            .all(|b| matches!(b, b'a'..=b'z' | b'0'..=b'9' | b'.' | b'-')),
    };
    let path_ok = path
        .bytes()
        .all(|b| matches!(b, b'a'..=b'z' | b'0'..=b'9' | b'.' | b'_' | b'/' | b'-'));
    reg_ok && path_ok
}

fn hex_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        use std::fmt::Write;
        let _ = write!(&mut out, "{b:02x}");
    }
    out
}

pub fn sha256_file(path: &Path) -> Result<String> {
    let mut file = fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(format!("sha256:{}", hex_encode(&hasher.finalize())))
}

pub fn parse_images(values: &[String]) -> Result<BTreeMap<String, ImageMeta>> {
    let known: HashMap<&str, &str> = COMPONENTS.iter().copied().collect();
    let mut images: BTreeMap<String, ImageMeta> = BTreeMap::new();
    for value in values {
        let Some((component, reference)) = value.split_once('=') else {
            bail!("image must use COMPONENT=REFERENCE form: {value}");
        };
        ensure!(
            known.contains_key(component),
            "unknown image component: {component}"
        );
        ensure!(
            !images.contains_key(component),
            "duplicate image component: {component}"
        );
        let Some((repository, digest)) = reference.split_once('@') else {
            bail!("image must be locked to a sha256 digest: {component}");
        };
        let Some(hex_part) = digest.strip_prefix("sha256:") else {
            bail!("image must be locked to a sha256 digest: {component}");
        };
        ensure!(
            is_valid_repository(repository) && is_lower_hex(hex_part, 64),
            "image must be locked to a sha256 digest: {component}"
        );
        let expected_suffix = format!("/agent-browser-cloud-{component}");
        ensure!(
            repository.ends_with(&expected_suffix),
            "{component} image repository must end with {expected_suffix}"
        );
        images.insert(
            component.to_owned(),
            ImageMeta {
                reference: reference.to_owned(),
                repository: repository.to_owned(),
                digest: digest.to_owned(),
            },
        );
    }
    let mut missing: Vec<&str> = COMPONENTS
        .iter()
        .map(|(c, _)| *c)
        .filter(|c| !images.contains_key(*c))
        .collect();
    missing.sort_unstable();
    ensure!(
        missing.is_empty(),
        "missing image components: {}",
        missing.join(",")
    );
    Ok(images)
}

fn validate_spdx_file(component: &str, path: &Path) -> Result<()> {
    ensure!(path.is_file(), "SBOM evidence is missing: {component}");
    let raw = fs::read_to_string(path)
        .map_err(|e| anyhow::anyhow!("SBOM evidence is invalid: {component}: {e}"))?;
    let doc: Value = serde_json::from_str(&raw)
        .map_err(|e| anyhow::anyhow!("SBOM evidence is invalid: {component}: {e}"))?;
    let ok = doc
        .as_object()
        .and_then(|o| o.get("spdxVersion"))
        .and_then(Value::as_str)
        .is_some_and(|v| v.starts_with("SPDX-"));
    ensure!(
        ok,
        "SBOM evidence is not an SPDX JSON document: {component}"
    );
    Ok(())
}

pub fn parse_evidence(values: &[String]) -> Result<BTreeMap<String, PathBuf>> {
    let known: HashMap<&str, &str> = COMPONENTS.iter().copied().collect();
    let mut evidence: BTreeMap<String, PathBuf> = BTreeMap::new();
    for value in values {
        let Some((component, raw_path)) = value.split_once('=') else {
            bail!("evidence must use COMPONENT=PATH form: {value}");
        };
        ensure!(
            known.contains_key(component),
            "unknown evidence component: {component}"
        );
        ensure!(
            !evidence.contains_key(component),
            "duplicate evidence component: {component}"
        );
        let path = PathBuf::from(raw_path);
        validate_spdx_file(component, &path)?;
        evidence.insert(component.to_owned(), fs::canonicalize(&path)?);
    }
    let mut missing: Vec<&str> = COMPONENTS
        .iter()
        .map(|(c, _)| *c)
        .filter(|c| !evidence.contains_key(*c))
        .collect();
    missing.sort_unstable();
    ensure!(
        missing.is_empty(),
        "missing SBOM evidence: {}",
        missing.join(",")
    );
    Ok(evidence)
}

pub fn render_kustomization(images: &BTreeMap<String, ImageMeta>) -> String {
    let mut lines = vec![
        "apiVersion: kustomize.config.k8s.io/v1beta1".to_owned(),
        "kind: Kustomization".to_owned(),
        "resources:".to_owned(),
        "  - ../base".to_owned(),
        "images:".to_owned(),
    ];
    for (component, base_name) in COMPONENTS {
        if let Some(image) = images.get(*component) {
            lines.push(format!("  - name: {base_name}"));
            lines.push(format!("    newName: {}", image.repository));
            lines.push(format!("    digest: {}", image.digest));
        }
    }
    format!("{}\n", lines.join("\n"))
}

fn copy_dir_recursive(src: &Path, dst: &Path) -> Result<()> {
    fs::create_dir_all(dst)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let ty = entry.file_type()?;
        let target = dst.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_recursive(&entry.path(), &target)?;
        } else {
            fs::copy(entry.path(), target)?;
        }
    }
    Ok(())
}

fn images_to_value(images: &BTreeMap<String, ImageMeta>) -> Value {
    let mut out = Map::new();
    for (comp, meta) in images {
        let mut inner = Map::new();
        inner.insert("digest".to_owned(), Value::String(meta.digest.clone()));
        inner.insert(
            "reference".to_owned(),
            Value::String(meta.reference.clone()),
        );
        inner.insert(
            "repository".to_owned(),
            Value::String(meta.repository.clone()),
        );
        out.insert(comp.clone(), Value::Object(inner));
    }
    Value::Object(out)
}

pub fn render_bundle(args: &RenderArgs) -> Result<()> {
    ensure!(
        is_lower_hex(&args.source_commit, 40),
        "source commit must be a full lowercase Git SHA"
    );
    let images = parse_images(&args.images)?;
    let evidence_sources = parse_evidence(&args.evidence)?;
    let base_dir = fs::canonicalize(&args.base_dir)
        .map_err(|_| anyhow::anyhow!("Kubernetes base is missing: {}", args.base_dir.display()))?;
    ensure!(
        base_dir.join("kustomization.yaml").is_file(),
        "Kubernetes base is missing: {}",
        base_dir.display()
    );
    ensure!(
        !args.output.exists(),
        "output directory already exists: {}",
        args.output.display()
    );

    fs::create_dir_all(&args.output)?;
    copy_dir_recursive(&base_dir, &args.output.join("base"))?;
    let production = args.output.join("production");
    fs::create_dir_all(&production)?;
    let kustomization_path = production.join("kustomization.yaml");
    fs::write(&kustomization_path, render_kustomization(&images))?;

    let evidence_dir = args.output.join("evidence");
    fs::create_dir_all(&evidence_dir)?;
    let mut evidence_map = Map::new();
    let mut sorted_components: Vec<&str> = COMPONENTS.iter().map(|(c, _)| *c).collect();
    sorted_components.sort_unstable();
    for component in sorted_components {
        let dest = evidence_dir.join(format!("{component}.spdx.json"));
        fs::copy(&evidence_sources[component], &dest)?;
        let mut entry = Map::new();
        entry.insert("digest".to_owned(), Value::String(sha256_file(&dest)?));
        entry.insert(
            "mediaType".to_owned(),
            Value::String(SPDX_MEDIA_TYPE.to_owned()),
        );
        entry.insert(
            "path".to_owned(),
            Value::String(format!("evidence/{component}.spdx.json")),
        );
        evidence_map.insert(component.to_owned(), Value::Object(entry));
    }

    let mut deployment = Map::new();
    deployment.insert(
        "entrypoint".to_owned(),
        Value::String("production/kustomization.yaml".to_owned()),
    );
    deployment.insert(
        "kustomizationDigest".to_owned(),
        Value::String(sha256_file(&kustomization_path)?),
    );

    let mut manifest = Map::new();
    manifest.insert("deployment".to_owned(), Value::Object(deployment));
    manifest.insert("evidence".to_owned(), Value::Object(evidence_map));
    manifest.insert("images".to_owned(), images_to_value(&images));
    manifest.insert(
        "schemaVersion".to_owned(),
        Value::Number(SCHEMA_VERSION.into()),
    );
    manifest.insert(
        "sourceCommit".to_owned(),
        Value::String(args.source_commit.clone()),
    );

    let manifest_text = format!(
        "{}\n",
        serde_json::to_string_pretty(&Value::Object(manifest))?
    );
    fs::write(args.output.join("release-manifest.json"), manifest_text)?;

    verify_bundle(&args.output)
}

pub fn verify_bundle(bundle: &Path) -> Result<()> {
    let manifest_path = bundle.join("release-manifest.json");
    let raw = fs::read_to_string(&manifest_path)
        .map_err(|e| anyhow::anyhow!("invalid release manifest: {e}"))?;
    let manifest: Value =
        serde_json::from_str(&raw).map_err(|e| anyhow::anyhow!("invalid release manifest: {e}"))?;
    ensure!(
        manifest.get("schemaVersion").and_then(Value::as_i64) == Some(SCHEMA_VERSION),
        "unsupported release manifest schema"
    );
    let commit = manifest
        .get("sourceCommit")
        .and_then(Value::as_str)
        .unwrap_or_default();
    ensure!(
        is_lower_hex(commit, 40),
        "release manifest source commit is invalid"
    );

    let raw_images = manifest
        .get("images")
        .and_then(Value::as_object)
        .ok_or_else(|| anyhow::anyhow!("release manifest images are missing"))?;
    let mut values = Vec::with_capacity(COMPONENTS.len());
    for (component, _) in COMPONENTS {
        let reference = raw_images
            .get(*component)
            .and_then(Value::as_object)
            .and_then(|o| o.get("reference"))
            .and_then(Value::as_str)
            .ok_or_else(|| anyhow::anyhow!("release manifest image is missing: {component}"))?;
        values.push(format!("{component}={reference}"));
    }
    let images = parse_images(&values)?;
    ensure!(
        Value::Object(raw_images.clone()) == images_to_value(&images),
        "release manifest image metadata is not canonical"
    );

    let raw_evidence = manifest
        .get("evidence")
        .and_then(Value::as_object)
        .ok_or_else(|| anyhow::anyhow!("release manifest evidence is missing"))?;
    let mut expected_evidence = Map::new();
    let mut sorted_components: Vec<&str> = COMPONENTS.iter().map(|(c, _)| *c).collect();
    sorted_components.sort_unstable();
    for component in sorted_components {
        let rel_path = format!("evidence/{component}.spdx.json");
        let entry = raw_evidence
            .get(component)
            .and_then(Value::as_object)
            .ok_or_else(|| anyhow::anyhow!("release manifest evidence is invalid: {component}"))?;
        ensure!(
            entry.get("path").and_then(Value::as_str) == Some(rel_path.as_str())
                && entry.get("mediaType").and_then(Value::as_str) == Some(SPDX_MEDIA_TYPE),
            "release manifest evidence is invalid: {component}"
        );
        let ev_path = bundle.join(&rel_path);
        validate_spdx_file(component, &ev_path)?;
        let mut canon = Map::new();
        canon.insert("digest".to_owned(), Value::String(sha256_file(&ev_path)?));
        canon.insert(
            "mediaType".to_owned(),
            Value::String(SPDX_MEDIA_TYPE.to_owned()),
        );
        canon.insert("path".to_owned(), Value::String(rel_path));
        expected_evidence.insert(component.to_owned(), Value::Object(canon));
    }
    ensure!(
        Value::Object(raw_evidence.clone()) == Value::Object(expected_evidence),
        "release manifest evidence metadata is not canonical"
    );

    let deployment = manifest
        .get("deployment")
        .and_then(Value::as_object)
        .ok_or_else(|| anyhow::anyhow!("release manifest deployment metadata is missing"))?;
    ensure!(
        deployment.get("entrypoint").and_then(Value::as_str)
            == Some("production/kustomization.yaml"),
        "release manifest deployment entrypoint is invalid"
    );
    let kustomization_path = bundle.join("production").join("kustomization.yaml");
    let actual_kust = fs::read_to_string(&kustomization_path)
        .map_err(|e| anyhow::anyhow!("production kustomization is missing: {e}"))?;
    ensure!(
        actual_kust == render_kustomization(&images),
        "production kustomization differs from signed image metadata"
    );
    ensure!(
        deployment
            .get("kustomizationDigest")
            .and_then(Value::as_str)
            == Some(sha256_file(&kustomization_path)?.as_str()),
        "production kustomization digest mismatch"
    );
    ensure!(
        bundle.join("base").join("kustomization.yaml").is_file(),
        "release bundle Kubernetes base is missing"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    #[test]
    fn renders_verifies_and_detects_tampering_in_release_bundle() {
        let dir =
            std::env::temp_dir().join(format!("release-bundle-test-{}", Uuid::new_v4().simple()));
        let base = dir.join("k8s-base");
        let sbom_dir = dir.join("sboms");
        let output = dir.join("bundle");
        fs::create_dir_all(&base).unwrap();
        fs::create_dir_all(&sbom_dir).unwrap();
        fs::write(base.join("kustomization.yaml"), "resources: []\n").unwrap();

        let mut images = Vec::new();
        let mut evidence = Vec::new();
        for (idx, (comp, repo)) in COMPONENTS.iter().enumerate() {
            let digest = format!("sha256:{:064x}", idx + 1);
            images.push(format!("{comp}={repo}@{digest}"));
            let sbom_path = sbom_dir.join(format!("{comp}.spdx.json"));
            fs::write(
                &sbom_path,
                format!("{{\"spdxVersion\":\"SPDX-2.3\",\"name\":\"{comp}\"}}\n"),
            )
            .unwrap();
            evidence.push(format!("{comp}={}", sbom_path.display()));
        }

        let args = RenderArgs {
            source_commit: "a".repeat(40),
            output: output.clone(),
            base_dir: base,
            images,
            evidence,
        };
        render_bundle(&args).unwrap();
        verify_bundle(&output).unwrap();

        // Tampering with kustomization MUST fail verification.
        let kust = output.join("production").join("kustomization.yaml");
        fs::write(&kust, "tampered\n").unwrap();
        assert!(verify_bundle(&output).is_err());

        let _ = fs::remove_dir_all(&dir);
    }
}
