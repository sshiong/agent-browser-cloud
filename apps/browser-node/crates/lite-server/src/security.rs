//! Authentication token helpers, SSRF URL fencing, and Host/Origin guards for L0 Lite.

use anyhow::{bail, ensure, Result};
use reqwest::Url;
use ring::rand::{SecureRandom, SystemRandom};
use sha2::{Digest, Sha256};
use std::net::IpAddr;

/// Generates a high-entropy `bcl_<64 hex chars>` bearer token using OS CSPRNG.
pub fn generate_agent_token() -> Result<String> {
    let rng = SystemRandom::new();
    let mut bytes = [0u8; 32];
    rng.fill(&mut bytes)
        .map_err(|_| anyhow::anyhow!("CSPRNG failed while generating agent token"))?;
    Ok(format!("bcl_{}", hex_encode(&bytes)))
}

/// Computes lowercase hex SHA-256 of a bearer token (only the hash is persisted in SQLite).
pub fn hash_token(plaintext: &str) -> String {
    let digest = Sha256::digest(plaintext.as_bytes());
    hex_encode(&digest)
}

/// Constant-time comparison of two equal-length hex digests.
pub fn constant_time_eq(left: &str, right: &str) -> bool {
    let a = left.as_bytes();
    let b = right.as_bytes();
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

pub fn hex_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        use std::fmt::Write;
        let _ = write!(&mut out, "{byte:02x}");
    }
    out
}

/// Validates an external navigation URL against SSRF and unsafe browser schemes.
///
/// Always rejects non-HTTP(S) schemes (`file://`, `chrome://`, `javascript:`, `data:`) and
/// cloud metadata endpoints (`169.254.169.254`, `metadata.google.internal`). Public internet
/// hosts are always allowed. Private/intranet hosts are blocked unless `allow_private_network`
/// is globally enabled or matched by per-environment `allowed_private_hosts`.
pub fn validate_navigation_url(raw: &str, allow_private_network: bool) -> Result<String> {
    validate_navigation_url_with_policy(raw, allow_private_network, &[])
}

pub fn validate_navigation_url_with_policy(
    raw: &str,
    global_allow_private: bool,
    allowed_private_hosts: &[String],
) -> Result<String> {
    let trimmed = raw.trim();
    ensure!(
        !trimmed.is_empty() && trimmed.len() <= 4096,
        "NAVIGATION_URL_INVALID"
    );
    let parsed = Url::parse(trimmed).map_err(|_| anyhow::anyhow!("NAVIGATION_URL_INVALID"))?;
    let scheme = parsed.scheme();
    ensure!(
        scheme == "http" || scheme == "https",
        "NAVIGATION_SCHEME_FORBIDDEN"
    );
    let host = parsed
        .host_str()
        .ok_or_else(|| anyhow::anyhow!("NAVIGATION_HOST_MISSING"))?
        .to_ascii_lowercase();
    let port = parsed.port_or_known_default().unwrap_or(80);
    if is_cloud_metadata_host(&host) {
        bail!("NAVIGATION_SSRF_BLOCKED");
    }
    if is_private_or_intranet_host(&host)
        && !is_private_target_authorized(
            &host,
            port,
            parse_literal_ip(&host),
            global_allow_private,
            allowed_private_hosts,
        )
    {
        bail!("PRIVATE_NETWORK_HOST_NOT_AUTHORIZED:{host}:{port}");
    }
    Ok(parsed.to_string())
}

pub fn is_cloud_metadata_host(host: &str) -> bool {
    let lower = host
        .trim()
        .to_ascii_lowercase()
        .trim_start_matches('[')
        .trim_end_matches(']')
        .to_owned();
    matches!(
        lower.as_str(),
        "metadata.google.internal"
            | "metadata.goog"
            | "169.254.169.254"
            | "fd00:ec2::254"
            | "100.100.100.200"
    )
}

pub fn parse_literal_ip(host: &str) -> Option<IpAddr> {
    let unbracketed = host.trim().trim_start_matches('[').trim_end_matches(']');
    unbracketed.parse::<IpAddr>().ok()
}

pub fn is_private_or_intranet_host(host: &str) -> bool {
    let lower = host.trim().to_ascii_lowercase();
    if lower.is_empty() {
        return true;
    }
    if lower == "localhost"
        || lower.ends_with(".localhost")
        || lower.ends_with(".local")
        || lower.ends_with(".internal")
        || lower.ends_with(".lan")
        || lower.ends_with(".home.arpa")
    {
        return true;
    }
    if let Some(ip) = parse_literal_ip(&lower) {
        return is_non_public_ip(ip);
    }
    // Single-label hostnames (e.g. `intranet`, `grafana`) resolve inside private LAN/Docker DNS.
    !lower.contains('.')
}

pub fn normalize_private_host_rules(raw_rules: &[String]) -> Result<Vec<String>> {
    ensure!(raw_rules.len() <= 64, "PRIVATE_HOST_RULES_LIMIT_EXCEEDED");
    let mut out = Vec::with_capacity(raw_rules.len());
    for raw in raw_rules {
        let rule = raw.trim().to_ascii_lowercase();
        ensure!(
            !rule.is_empty()
                && rule.len() <= 253
                && rule.is_ascii()
                && !rule.chars().any(char::is_whitespace)
                && rule != "*"
                && rule != "*:*",
            "PRIVATE_HOST_RULE_INVALID"
        );
        // Reject cloud metadata rules even if explicitly requested.
        let host_part = split_rule_host_port(&rule).0;
        ensure!(
            !is_cloud_metadata_host(host_part),
            "PRIVATE_HOST_RULE_FORBIDDEN"
        );
        if let Some((ip_str, prefix_str)) = rule.split_once('/') {
            let ip: std::net::Ipv4Addr = ip_str
                .parse()
                .map_err(|_| anyhow::anyhow!("PRIVATE_HOST_RULE_INVALID"))?;
            let prefix: u8 = prefix_str
                .parse()
                .map_err(|_| anyhow::anyhow!("PRIVATE_HOST_RULE_INVALID"))?;
            ensure!(
                (8..=32).contains(&prefix) && is_non_public_ip(IpAddr::V4(ip)),
                "PRIVATE_HOST_RULE_INVALID"
            );
        }
        if !out.contains(&rule) {
            out.push(rule);
        }
    }
    Ok(out)
}

pub fn is_private_target_authorized(
    host: &str,
    port: u16,
    resolved_ip: Option<IpAddr>,
    global_allow_private: bool,
    allowed_private_hosts: &[String],
) -> bool {
    if is_cloud_metadata_host(host) {
        return false;
    }
    if let Some(ip) = resolved_ip {
        if is_cloud_metadata_host(&ip.to_string()) {
            return false;
        }
    }
    if global_allow_private {
        return true;
    }
    allowed_private_hosts
        .iter()
        .any(|rule| private_host_rule_matches(host, port, resolved_ip, rule))
}

fn split_rule_host_port(rule: &str) -> (&str, Option<&str>) {
    if let Some(rest) = rule.strip_prefix('[') {
        if let Some((host, after)) = rest.split_once(']') {
            if let Some(port) = after.strip_prefix(':') {
                return (host, Some(port));
            }
            return (host, None);
        }
    }
    if rule.matches(':').count() == 1 {
        if let Some((h, p)) = rule.split_once(':') {
            return (h, Some(p));
        }
    }
    (rule, None)
}

fn private_host_rule_matches(
    host: &str,
    port: u16,
    resolved_ip: Option<IpAddr>,
    rule: &str,
) -> bool {
    let norm_rule = rule.trim().to_ascii_lowercase();
    if let Some((cidr_ip, prefix_str)) = norm_rule.split_once('/') {
        let Ok(net) = cidr_ip.parse::<std::net::Ipv4Addr>() else {
            return false;
        };
        let Ok(prefix) = prefix_str.parse::<u32>() else {
            return false;
        };
        if prefix == 0 || prefix > 32 {
            return false;
        }
        let mask = u32::MAX.checked_shl(32 - prefix).unwrap_or(0);
        let candidate_ip = resolved_ip.or_else(|| parse_literal_ip(host));
        return match candidate_ip {
            Some(IpAddr::V4(v4)) => (u32::from(v4) & mask) == (u32::from(net) & mask),
            Some(IpAddr::V6(v6)) => v6
                .to_ipv4_mapped()
                .is_some_and(|v4| (u32::from(v4) & mask) == (u32::from(net) & mask)),
            None => false,
        };
    }

    let (rule_host, rule_port) = split_rule_host_port(&norm_rule);
    if let Some(rp) = rule_port {
        if rp != "*" && rp.parse::<u16>().ok() != Some(port) {
            return false;
        }
    }

    let norm_host = host
        .trim()
        .to_ascii_lowercase()
        .trim_start_matches('[')
        .trim_end_matches(']')
        .to_owned();
    if let Some(suffix) = rule_host.strip_prefix("*.") {
        return norm_host == suffix || norm_host.ends_with(&format!(".{suffix}"));
    }
    if norm_host == rule_host {
        return true;
    }
    if let Some(ip) = resolved_ip {
        if ip.to_string().eq_ignore_ascii_case(rule_host) {
            return true;
        }
    }
    false
}

/// Validates a per-session proxy URL (`http://`, `https://`, `socks5://`).
pub fn validate_proxy_url(raw: &str) -> Result<String> {
    let trimmed = raw.trim();
    ensure!(
        !trimmed.is_empty() && trimmed.len() <= 1024,
        "PROXY_URL_INVALID"
    );
    let parsed = Url::parse(trimmed).map_err(|_| anyhow::anyhow!("PROXY_URL_INVALID"))?;
    ensure!(
        matches!(parsed.scheme(), "http" | "https" | "socks5" | "socks5h"),
        "PROXY_SCHEME_INVALID"
    );
    ensure!(parsed.host_str().is_some(), "PROXY_HOST_INVALID");
    Ok(parsed.to_string())
}

/// Validates an identifier prefix (`ses_`, `prf_`, `tok_`, `chk_`) to prevent path traversal.
pub fn validate_identifier(value: &str, prefix: &str) -> Result<()> {
    ensure!(
        value.starts_with(prefix)
            && value.len() > prefix.len()
            && value.len() <= 64
            && value
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-'),
        "IDENTIFIER_INVALID"
    );
    Ok(())
}

/// Matches a Host or Origin header against comma/space-separated glob patterns (e.g. `127.0.0.1:*`).
pub fn matches_allowlist(value: &str, patterns: &[String]) -> bool {
    let candidate = value.trim().to_ascii_lowercase();
    if candidate.is_empty() {
        return false;
    }
    patterns.iter().any(|pattern| {
        let pat = pattern.trim().to_ascii_lowercase();
        if pat == "*" {
            return true;
        }
        if let Some(prefix) = pat.strip_suffix(":*") {
            candidate == prefix
                || candidate
                    .strip_prefix(prefix)
                    .and_then(|rest| rest.strip_prefix(':'))
                    .is_some_and(|port| {
                        !port.is_empty() && port.bytes().all(|b| b.is_ascii_digit())
                    })
        } else {
            candidate == pat
        }
    })
}

pub fn is_non_public_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            v4.is_loopback()
                || v4.is_private()
                || v4.is_link_local()
                || v4.is_broadcast()
                || v4.is_unspecified()
                || v4.is_multicast()
                || (v4.octets()[0] == 100 && (v4.octets()[1] & 0b1100_0000) == 64)
        }
        IpAddr::V6(v6) => {
            v6.is_loopback()
                || v6.is_unspecified()
                || v6.is_multicast()
                || ((v6.segments()[0] & 0xfe00) == 0xfc00)
                || ((v6.segments()[0] & 0xffc0) == 0xfe80)
                || v6
                    .to_ipv4_mapped()
                    .is_some_and(|v4| is_non_public_ip(IpAddr::V4(v4)))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_high_entropy_unique_tokens_and_verifies_hash() {
        let t1 = generate_agent_token().unwrap();
        let t2 = generate_agent_token().unwrap();
        assert!(t1.starts_with("bcl_"));
        assert_eq!(t1.len(), 68);
        assert_ne!(t1, t2);
        assert!(constant_time_eq(&hash_token(&t1), &hash_token(&t1)));
        assert!(!constant_time_eq(&hash_token(&t1), &hash_token(&t2)));
    }

    #[test]
    fn ssrf_guard_blocks_unsafe_schemes_metadata_and_private_ips() {
        assert!(validate_navigation_url("https://example.com/path", false).is_ok());
        assert!(validate_navigation_url("file:///etc/passwd", true).is_err());
        assert!(validate_navigation_url("chrome://settings", true).is_err());
        assert!(validate_navigation_url("javascript:alert(1)", true).is_err());
        assert!(validate_navigation_url("http://169.254.169.254/latest/meta-data", true).is_err());
        assert!(validate_navigation_url("http://127.0.0.1:8130/mcp", false).is_err());
        assert!(validate_navigation_url("http://10.0.0.5/internal", false).is_err());
        assert!(validate_navigation_url("http://127.0.0.1:9999/test", true).is_ok());
    }

    #[test]
    fn allowlist_matches_exact_and_wildcard_port_patterns() {
        let patterns = vec![
            "127.0.0.1:*".to_owned(),
            "http://localhost:*".to_owned(),
            "https://browser.example.com".to_owned(),
        ];
        assert!(matches_allowlist("127.0.0.1:8130", &patterns));
        assert!(matches_allowlist("http://localhost:3000", &patterns));
        assert!(matches_allowlist("https://browser.example.com", &patterns));
        assert!(!matches_allowlist("https://evil.invalid", &patterns));
        assert!(!matches_allowlist("127.0.0.1:abc", &patterns));
    }
}
