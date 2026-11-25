//! Per-environment loopback HTTP/CONNECT egress guard for L0 Lite Chromium sessions.
//!
//! Public internet requests flow straight through at native speed. Private/intranet hosts
//! (loopback, RFC1918, ULA, `.local`/`.internal`, single-label hosts, or domains DNS-resolving to
//! non-public IPs) are permitted ONLY when globally enabled or explicitly authorized in the
//! environment's `allowed_private_hosts` list. Cloud metadata endpoints are ALWAYS blocked.
//!
//! Crucially, rejecting an unauthorized request only returns HTTP 403 for that individual network
//! request — the browser process, session, and running task continue uninterrupted.

use crate::security::{
    is_cloud_metadata_host, is_non_public_ip, is_private_or_intranet_host,
    is_private_target_authorized,
};
use anyhow::{bail, ensure, Result};
use base64::Engine as _;
use reqwest::Url;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{lookup_host, TcpListener, TcpStream};
use tokio::sync::Semaphore;
use tokio::task::AbortHandle;
use tokio::time::timeout;

const MAX_CONCURRENT_EGRESS_CONNS: usize = 128;
const HEADER_READ_TIMEOUT: Duration = Duration::from_secs(10);
const UPSTREAM_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Clone)]
enum UpstreamProxyConfig {
    Http {
        host: String,
        port: u16,
        auth_header: Option<String>,
    },
    Socks5 {
        host: String,
        port: u16,
        credentials: Option<(String, String)>,
    },
}

impl UpstreamProxyConfig {
    fn parse(raw: &str) -> Result<Self> {
        let parsed = Url::parse(raw.trim()).map_err(|_| anyhow::anyhow!("PROXY_URL_INVALID"))?;
        let host = parsed
            .host_str()
            .ok_or_else(|| anyhow::anyhow!("PROXY_HOST_INVALID"))?
            .to_owned();
        let scheme = parsed.scheme();
        match scheme {
            "http" => {
                let port = parsed.port_or_known_default().unwrap_or(80);
                let auth_header = if !parsed.username().is_empty() || parsed.password().is_some() {
                    let raw_creds = format!(
                        "{}:{}",
                        parsed.username(),
                        parsed.password().unwrap_or_default()
                    );
                    Some(format!(
                        "Basic {}",
                        base64::engine::general_purpose::STANDARD.encode(raw_creds.as_bytes())
                    ))
                } else {
                    None
                };
                Ok(Self::Http {
                    host,
                    port,
                    auth_header,
                })
            }
            "socks5" | "socks5h" => {
                let port = parsed.port().unwrap_or(1080);
                let credentials = if !parsed.username().is_empty() || parsed.password().is_some() {
                    Some((
                        parsed.username().to_owned(),
                        parsed.password().unwrap_or_default().to_owned(),
                    ))
                } else {
                    None
                };
                Ok(Self::Socks5 {
                    host,
                    port,
                    credentials,
                })
            }
            _ => bail!("PROXY_SCHEME_UNSUPPORTED_FOR_EGRESS_GUARD"),
        }
    }
}

#[derive(Debug, Clone)]
pub struct EgressPolicyRules {
    pub permanent_hosts: Vec<String>,
    pub temporary_hosts: Vec<(String, Option<Instant>)>,
}

impl EgressPolicyRules {
    pub fn effective_hosts(&self, now: Instant) -> Vec<String> {
        let mut out = self.permanent_hosts.clone();
        for (rule, expires_at) in &self.temporary_hosts {
            if expires_at.is_none_or(|exp| now < exp) && !out.contains(rule) {
                out.push(rule.clone());
            }
        }
        out
    }
}

pub struct EgressGuardHandle {
    pub proxy_url: String,
    policy: Arc<RwLock<EgressPolicyRules>>,
    abort_handle: AbortHandle,
}

impl EgressGuardHandle {
    pub async fn spawn(
        global_allow_private: bool,
        initial_permanent_hosts: Vec<String>,
        initial_temporary_hosts: Vec<(String, Option<Instant>)>,
    ) -> Result<Self> {
        Self::spawn_with_upstream(
            global_allow_private,
            initial_permanent_hosts,
            initial_temporary_hosts,
            None,
        )
        .await
    }

    pub async fn spawn_with_upstream(
        global_allow_private: bool,
        initial_permanent_hosts: Vec<String>,
        initial_temporary_hosts: Vec<(String, Option<Instant>)>,
        upstream_proxy: Option<String>,
    ) -> Result<Self> {
        let parsed_upstream = match upstream_proxy.as_deref() {
            Some(raw) if !raw.trim().is_empty() => Some(Arc::new(UpstreamProxyConfig::parse(raw)?)),
            _ => None,
        };
        let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
        let port = listener.local_addr()?.port();
        let policy = Arc::new(RwLock::new(EgressPolicyRules {
            permanent_hosts: initial_permanent_hosts,
            temporary_hosts: initial_temporary_hosts,
        }));
        let policy_ref = policy.clone();
        let conn_limit = Arc::new(Semaphore::new(MAX_CONCURRENT_EGRESS_CONNS));

        let task = tokio::spawn(async move {
            loop {
                let Ok((mut stream, _peer)) = listener.accept().await else {
                    break;
                };
                let _ = stream.set_nodelay(true);
                let Ok(permit) = conn_limit.clone().try_acquire_owned() else {
                    tokio::spawn(async move {
                        write_error(&mut stream, 503, "EGRESS_CONCURRENCY_LIMIT_REACHED").await;
                    });
                    continue;
                };
                let rules = policy_ref.clone();
                let upstream_cfg = parsed_upstream.clone();
                tokio::spawn(async move {
                    let _permit = permit;
                    let _ = handle_client(stream, global_allow_private, rules, upstream_cfg).await;
                });
            }
        });

        Ok(Self {
            proxy_url: format!("http://127.0.0.1:{port}"),
            policy,
            abort_handle: task.abort_handle(),
        })
    }

    pub fn set_allowed_private_hosts(&self, hosts: Vec<String>) {
        if let Ok(mut guard) = self.policy.write() {
            guard.permanent_hosts = hosts;
        }
    }

    pub fn set_policy(
        &self,
        permanent_hosts: Vec<String>,
        temporary_hosts: Vec<(String, Option<Instant>)>,
    ) {
        if let Ok(mut guard) = self.policy.write() {
            guard.permanent_hosts = permanent_hosts;
            guard.temporary_hosts = temporary_hosts;
        }
    }

    pub fn clear_temporary_hosts(&self) {
        if let Ok(mut guard) = self.policy.write() {
            guard.temporary_hosts.clear();
        }
    }
}

impl Drop for EgressGuardHandle {
    fn drop(&mut self) {
        self.abort_handle.abort();
    }
}

async fn handle_client(
    mut client: TcpStream,
    global_allow_private: bool,
    allowed_rules: Arc<RwLock<EgressPolicyRules>>,
    upstream_proxy: Option<Arc<UpstreamProxyConfig>>,
) -> Result<()> {
    let mut buf = Vec::with_capacity(4096);
    let mut chunk = [0u8; 2048];
    let header_deadline = tokio::time::Instant::now() + HEADER_READ_TIMEOUT;
    let header_end = loop {
        let remaining = header_deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            write_error(&mut client, 408, "HEADER_READ_TIMEOUT").await;
            return Ok(());
        }
        let n = match timeout(remaining, client.read(&mut chunk)).await {
            Ok(Ok(n)) => n,
            Ok(Err(e)) => return Err(e.into()),
            Err(_) => {
                write_error(&mut client, 408, "HEADER_READ_TIMEOUT").await;
                return Ok(());
            }
        };
        if n == 0 {
            return Ok(());
        }
        buf.extend_from_slice(&chunk[..n]);
        if let Some(pos) = find_header_end(&buf) {
            break pos;
        }
        if buf.len() > 65_536 {
            write_error(&mut client, 431, "HEADER_TOO_LARGE").await;
            return Ok(());
        }
    };

    let header_bytes = &buf[..header_end];
    let body_prefix = &buf[header_end + 4..];
    let header_text = String::from_utf8_lossy(header_bytes);
    let mut lines = header_text.split("\r\n");
    let request_line = lines.next().unwrap_or_default();
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or_default();
    let target = parts.next().unwrap_or_default();
    let version = parts.next().unwrap_or("HTTP/1.1");
    if method.is_empty() || target.is_empty() {
        write_error(&mut client, 400, "MALFORMED_PROXY_REQUEST").await;
        return Ok(());
    }

    let now = Instant::now();
    let rules_snapshot = allowed_rules
        .read()
        .map(|g| g.effective_hosts(now))
        .unwrap_or_default();

    if method.eq_ignore_ascii_case("CONNECT") {
        let Some((host, port)) = parse_authority(target, 443) else {
            write_error(&mut client, 400, "INVALID_CONNECT_TARGET").await;
            return Ok(());
        };
        let upstream_addr =
            match authorize_and_resolve(&host, port, global_allow_private, &rules_snapshot).await {
                Ok(addr) => addr,
                Err(reason) => {
                    write_error(&mut client, 403, &reason).await;
                    return Ok(());
                }
            };
        let (mut upstream, upstream_trailing) =
            match connect_for_tunnel(upstream_proxy.as_deref(), &host, port, upstream_addr).await {
                Ok(pair) => pair,
                Err((status, reason)) => {
                    write_error(&mut client, status, reason).await;
                    return Ok(());
                }
            };
        client
            .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            .await?;
        if !upstream_trailing.is_empty() {
            client.write_all(&upstream_trailing).await?;
        }
        if !body_prefix.is_empty() {
            upstream.write_all(body_prefix).await?;
        }
        let _ = tokio::io::copy_bidirectional(&mut client, &mut upstream).await;
        return Ok(());
    }

    // Standard HTTP proxy request (`GET http://host:port/path HTTP/1.1`).
    let parsed = match Url::parse(target) {
        Ok(u) if matches!(u.scheme(), "http" | "https") => u,
        _ => {
            write_error(&mut client, 400, "INVALID_PROXY_URL").await;
            return Ok(());
        }
    };
    let Some(host) = parsed.host_str().map(ToOwned::to_owned) else {
        write_error(&mut client, 400, "MISSING_TARGET_HOST").await;
        return Ok(());
    };
    let port = parsed.port_or_known_default().unwrap_or(80);
    let upstream_addr =
        match authorize_and_resolve(&host, port, global_allow_private, &rules_snapshot).await {
            Ok(addr) => addr,
            Err(reason) => {
                write_error(&mut client, 403, &reason).await;
                return Ok(());
            }
        };

    match upstream_proxy.as_deref() {
        Some(UpstreamProxyConfig::Http {
            host: proxy_host,
            port: proxy_port,
            auth_header,
        }) => {
            let mut upstream = match connect_proxy_tcp(proxy_host, *proxy_port).await {
                Ok(s) => s,
                Err((status, reason)) => {
                    write_error(&mut client, status, reason).await;
                    return Ok(());
                }
            };
            let mut forwarded = format!("{method} {target} {version}\r\n");
            for line in lines {
                if line.is_empty() {
                    continue;
                }
                if starts_with_ignore_ascii_case(line, "proxy-connection:")
                    || starts_with_ignore_ascii_case(line, "proxy-authorization:")
                {
                    continue;
                }
                forwarded.push_str(line);
                forwarded.push_str("\r\n");
            }
            if let Some(auth) = auth_header {
                forwarded.push_str(&format!("Proxy-Authorization: {auth}\r\n"));
            }
            forwarded.push_str("\r\n");
            upstream.write_all(forwarded.as_bytes()).await?;
            if !body_prefix.is_empty() {
                upstream.write_all(body_prefix).await?;
            }
            let _ = tokio::io::copy_bidirectional(&mut client, &mut upstream).await;
            return Ok(());
        }
        Some(UpstreamProxyConfig::Socks5 {
            host: proxy_host,
            port: proxy_port,
            credentials,
        }) => {
            let mut upstream = match connect_via_socks5(
                proxy_host,
                *proxy_port,
                credentials.as_ref(),
                upstream_addr,
            )
            .await
            {
                Ok(s) => s,
                Err((status, reason)) => {
                    write_error(&mut client, status, reason).await;
                    return Ok(());
                }
            };
            write_origin_http_request(&mut upstream, method, &parsed, version, lines, body_prefix)
                .await?;
            let _ = tokio::io::copy_bidirectional(&mut client, &mut upstream).await;
            return Ok(());
        }
        None => {}
    }

    let mut upstream =
        match timeout(UPSTREAM_CONNECT_TIMEOUT, TcpStream::connect(upstream_addr)).await {
            Ok(Ok(s)) => {
                let _ = s.set_nodelay(true);
                s
            }
            Ok(Err(_)) => {
                write_error(&mut client, 502, "UPSTREAM_CONNECT_FAILED").await;
                return Ok(());
            }
            Err(_) => {
                write_error(&mut client, 504, "UPSTREAM_CONNECT_TIMEOUT").await;
                return Ok(());
            }
        };

    write_origin_http_request(&mut upstream, method, &parsed, version, lines, body_prefix).await?;
    let _ = tokio::io::copy_bidirectional(&mut client, &mut upstream).await;
    Ok(())
}

async fn write_origin_http_request<'a, I>(
    upstream: &mut TcpStream,
    method: &str,
    parsed: &Url,
    version: &str,
    lines: I,
    body_prefix: &[u8],
) -> Result<()>
where
    I: Iterator<Item = &'a str>,
{
    let mut origin_path = parsed.path().to_owned();
    if let Some(q) = parsed.query() {
        origin_path.push('?');
        origin_path.push_str(q);
    }
    let mut forwarded = format!("{method} {origin_path} {version}\r\n");
    for line in lines {
        if line.is_empty() {
            continue;
        }
        if starts_with_ignore_ascii_case(line, "proxy-connection:")
            || starts_with_ignore_ascii_case(line, "proxy-authorization:")
        {
            continue;
        }
        forwarded.push_str(line);
        forwarded.push_str("\r\n");
    }
    forwarded.push_str("\r\n");
    upstream.write_all(forwarded.as_bytes()).await?;
    if !body_prefix.is_empty() {
        upstream.write_all(body_prefix).await?;
    }
    Ok(())
}

async fn connect_proxy_tcp(
    host: &str,
    port: u16,
) -> std::result::Result<TcpStream, (u16, &'static str)> {
    let unbracketed = host.trim_start_matches('[').trim_end_matches(']');
    match timeout(
        UPSTREAM_CONNECT_TIMEOUT,
        TcpStream::connect((unbracketed, port)),
    )
    .await
    {
        Ok(Ok(s)) => {
            let _ = s.set_nodelay(true);
            Ok(s)
        }
        Ok(Err(_)) => Err((502, "UPSTREAM_PROXY_CONNECT_FAILED")),
        Err(_) => Err((504, "UPSTREAM_PROXY_CONNECT_TIMEOUT")),
    }
}

async fn connect_for_tunnel(
    upstream_proxy: Option<&UpstreamProxyConfig>,
    target_host: &str,
    target_port: u16,
    resolved_target: SocketAddr,
) -> std::result::Result<(TcpStream, Vec<u8>), (u16, &'static str)> {
    match upstream_proxy {
        None => match timeout(
            UPSTREAM_CONNECT_TIMEOUT,
            TcpStream::connect(resolved_target),
        )
        .await
        {
            Ok(Ok(s)) => {
                let _ = s.set_nodelay(true);
                Ok((s, Vec::new()))
            }
            Ok(Err(_)) => Err((502, "UPSTREAM_CONNECT_FAILED")),
            Err(_) => Err((504, "UPSTREAM_CONNECT_TIMEOUT")),
        },
        Some(UpstreamProxyConfig::Http {
            host,
            port,
            auth_header,
        }) => {
            let mut stream = connect_proxy_tcp(host, *port).await?;
            let authority = if target_host.contains(':') && !target_host.starts_with('[') {
                format!("[{target_host}]:{target_port}")
            } else {
                format!("{target_host}:{target_port}")
            };
            let mut req = format!("CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\n");
            if let Some(auth) = auth_header {
                req.push_str(&format!("Proxy-Authorization: {auth}\r\n"));
            }
            req.push_str("\r\n");
            if stream.write_all(req.as_bytes()).await.is_err() {
                return Err((502, "UPSTREAM_PROXY_WRITE_FAILED"));
            }
            let mut resp_buf = Vec::with_capacity(512);
            let mut chunk = [0u8; 512];
            let deadline = tokio::time::Instant::now() + HEADER_READ_TIMEOUT;
            let header_end = loop {
                let rem = deadline.saturating_duration_since(tokio::time::Instant::now());
                if rem.is_zero() {
                    return Err((504, "UPSTREAM_PROXY_CONNECT_TIMEOUT"));
                }
                let n = match timeout(rem, stream.read(&mut chunk)).await {
                    Ok(Ok(0)) => return Err((502, "UPSTREAM_PROXY_CLOSED")),
                    Ok(Ok(n)) => n,
                    Ok(Err(_)) => return Err((502, "UPSTREAM_PROXY_READ_FAILED")),
                    Err(_) => return Err((504, "UPSTREAM_PROXY_CONNECT_TIMEOUT")),
                };
                resp_buf.extend_from_slice(&chunk[..n]);
                if let Some(pos) = find_header_end(&resp_buf) {
                    break pos;
                }
                if resp_buf.len() > 16_384 {
                    return Err((502, "UPSTREAM_PROXY_HEADER_TOO_LARGE"));
                }
            };
            let status_line = String::from_utf8_lossy(&resp_buf[..header_end])
                .lines()
                .next()
                .unwrap_or_default()
                .to_owned();
            let status_code = status_line
                .split_whitespace()
                .nth(1)
                .and_then(|s| s.parse::<u16>().ok())
                .unwrap_or(0);
            if status_code != 200 {
                return Err((502, "UPSTREAM_PROXY_CONNECT_REJECTED"));
            }
            let trailing = resp_buf[header_end + 4..].to_vec();
            Ok((stream, trailing))
        }
        Some(UpstreamProxyConfig::Socks5 {
            host,
            port,
            credentials,
        }) => {
            let stream =
                connect_via_socks5(host, *port, credentials.as_ref(), resolved_target).await?;
            Ok((stream, Vec::new()))
        }
    }
}

async fn connect_via_socks5(
    proxy_host: &str,
    proxy_port: u16,
    credentials: Option<&(String, String)>,
    target: SocketAddr,
) -> std::result::Result<TcpStream, (u16, &'static str)> {
    let mut stream = connect_proxy_tcp(proxy_host, proxy_port).await?;
    let handshake = async {
        if let Some((user, pass)) = credentials {
            ensure!(
                user.len() <= 255 && pass.len() <= 255,
                "SOCKS5_CREDENTIALS_TOO_LONG"
            );
            stream.write_all(&[0x05, 0x02, 0x00, 0x02]).await?;
            let mut method_resp = [0u8; 2];
            stream.read_exact(&mut method_resp).await?;
            ensure!(method_resp[0] == 0x05, "SOCKS5_VERSION_INVALID");
            if method_resp[1] == 0x02 {
                let mut auth_req = Vec::with_capacity(3 + user.len() + pass.len());
                auth_req.push(0x01);
                auth_req.push(user.len() as u8);
                auth_req.extend_from_slice(user.as_bytes());
                auth_req.push(pass.len() as u8);
                auth_req.extend_from_slice(pass.as_bytes());
                stream.write_all(&auth_req).await?;
                let mut auth_resp = [0u8; 2];
                stream.read_exact(&mut auth_resp).await?;
                ensure!(auth_resp[1] == 0x00, "SOCKS5_AUTH_FAILED");
            } else {
                ensure!(method_resp[1] == 0x00, "SOCKS5_METHOD_REJECTED");
            }
        } else {
            stream.write_all(&[0x05, 0x01, 0x00]).await?;
            let mut method_resp = [0u8; 2];
            stream.read_exact(&mut method_resp).await?;
            ensure!(
                method_resp[0] == 0x05 && method_resp[1] == 0x00,
                "SOCKS5_METHOD_REJECTED"
            );
        }

        let mut req = vec![0x05, 0x01, 0x00];
        match target.ip() {
            IpAddr::V4(v4) => {
                req.push(0x01);
                req.extend_from_slice(&v4.octets());
            }
            IpAddr::V6(v6) => {
                req.push(0x04);
                req.extend_from_slice(&v6.octets());
            }
        }
        req.extend_from_slice(&target.port().to_be_bytes());
        stream.write_all(&req).await?;

        let mut head = [0u8; 4];
        stream.read_exact(&mut head).await?;
        ensure!(
            head[0] == 0x05 && head[1] == 0x00,
            "SOCKS5_CONNECT_REJECTED"
        );
        match head[3] {
            0x01 => {
                let mut rest = [0u8; 6];
                stream.read_exact(&mut rest).await?;
            }
            0x04 => {
                let mut rest = [0u8; 18];
                stream.read_exact(&mut rest).await?;
            }
            0x03 => {
                let mut len = [0u8; 1];
                stream.read_exact(&mut len).await?;
                let mut rest = vec![0u8; len[0] as usize + 2];
                stream.read_exact(&mut rest).await?;
            }
            _ => bail!("SOCKS5_ATYP_INVALID"),
        }
        Ok::<(), anyhow::Error>(())
    };
    match timeout(UPSTREAM_CONNECT_TIMEOUT, handshake).await {
        Ok(Ok(())) => Ok(stream),
        Ok(Err(_)) => Err((502, "UPSTREAM_SOCKS5_CONNECT_FAILED")),
        Err(_) => Err((504, "UPSTREAM_PROXY_CONNECT_TIMEOUT")),
    }
}

async fn authorize_and_resolve(
    host: &str,
    port: u16,
    global_allow_private: bool,
    allowed_private_hosts: &[String],
) -> std::result::Result<SocketAddr, String> {
    if is_cloud_metadata_host(host) {
        return Err("NAVIGATION_SSRF_BLOCKED".to_owned());
    }
    let host_is_private = is_private_or_intranet_host(host);
    if host_is_private
        && !is_private_target_authorized(
            host,
            port,
            None,
            global_allow_private,
            allowed_private_hosts,
        )
    {
        return Err(format!("PRIVATE_NETWORK_HOST_NOT_AUTHORIZED:{host}:{port}"));
    }

    let unbracketed = host.trim_start_matches('[').trim_end_matches(']');
    let addrs: Vec<SocketAddr> = lookup_host((unbracketed, port))
        .await
        .map_err(|_| "DNS_RESOLUTION_FAILED".to_owned())?
        .collect();
    if addrs.is_empty() {
        return Err("DNS_RESOLUTION_EMPTY".to_owned());
    }

    for addr in &addrs {
        let ip = addr.ip();
        if is_cloud_metadata_host(&ip.to_string()) {
            return Err("NAVIGATION_SSRF_BLOCKED".to_owned());
        }
        if is_non_public_ip(ip)
            && !is_private_target_authorized(
                host,
                port,
                Some(ip),
                global_allow_private,
                allowed_private_hosts,
            )
        {
            return Err(format!("PRIVATE_NETWORK_HOST_NOT_AUTHORIZED:{host}:{port}"));
        }
    }
    Ok(addrs[0])
}

fn parse_authority(raw: &str, default_port: u16) -> Option<(String, u16)> {
    if let Some(rest) = raw.strip_prefix('[') {
        let (host, after) = rest.split_once(']')?;
        let port = after
            .strip_prefix(':')
            .and_then(|p| p.parse::<u16>().ok())
            .unwrap_or(default_port);
        return Some((host.to_owned(), port));
    }
    if let Some((host, port_str)) = raw.rsplit_once(':') {
        let port = port_str.parse::<u16>().ok()?;
        Some((host.to_owned(), port))
    } else {
        Some((raw.to_owned(), default_port))
    }
}

fn starts_with_ignore_ascii_case(haystack: &str, prefix: &str) -> bool {
    haystack
        .get(..prefix.len())
        .is_some_and(|head| head.eq_ignore_ascii_case(prefix))
}

fn find_header_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

async fn write_error(stream: &mut TcpStream, status: u16, reason: &str) {
    let status_text = match status {
        400 => "Bad Request",
        403 => "Forbidden",
        408 => "Request Timeout",
        431 => "Request Header Fields Too Large",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        504 => "Gateway Timeout",
        _ => "Error",
    };
    let body = format!("{{\"error\":\"{reason}\"}}");
    let resp = format!(
        "HTTP/1.1 {status} {status_text}\r\nContent-Type: application/json\r\nX-BrowserCloud-Block-Reason: {reason}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let _ = stream.write_all(resp.as_bytes()).await;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn blocks_unauthorized_loopback_and_allows_after_live_environment_grant() {
        // Start a tiny local HTTP server simulating an internal admin panel.
        let internal = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let internal_port = internal.local_addr().unwrap().port();
        let server_task = tokio::spawn(async move {
            loop {
                let Ok((mut s, _)) = internal.accept().await else {
                    break;
                };
                tokio::spawn(async move {
                    let mut tmp = [0u8; 512];
                    let _ = s.read(&mut tmp).await;
                    let _ = s
                        .write_all(
                            b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\nADMIN_HOME",
                        )
                        .await;
                });
            }
        });

        let guard = EgressGuardHandle::spawn(false, vec![], vec![])
            .await
            .unwrap();
        let proxy = reqwest::Proxy::http(&guard.proxy_url).unwrap();
        let client = reqwest::Client::builder().proxy(proxy).build().unwrap();
        let target_url = format!("http://127.0.0.1:{internal_port}/dashboard");

        // 1. Before authorization: request is rejected with 403, guard keeps running cleanly.
        let blocked = client.get(&target_url).send().await.unwrap();
        assert_eq!(blocked.status().as_u16(), 403);
        assert!(blocked
            .headers()
            .get("x-browsercloud-block-reason")
            .unwrap()
            .to_str()
            .unwrap()
            .contains("PRIVATE_NETWORK_HOST_NOT_AUTHORIZED"));

        // 2. Temporary "once" authorization works live and expires when cleared or TTL lapses.
        guard.set_policy(vec![], vec![(format!("127.0.0.1:{internal_port}"), None)]);
        let once_allowed = client.get(&target_url).send().await.unwrap();
        assert_eq!(once_allowed.status().as_u16(), 200);
        assert_eq!(once_allowed.text().await.unwrap(), "ADMIN_HOME");

        guard.clear_temporary_hosts();
        let blocked_again = client.get(&target_url).send().await.unwrap();
        assert_eq!(blocked_again.status().as_u16(), 403);

        // 3. Permanent authorization survives clearing temporary grants.
        guard.set_allowed_private_hosts(vec![format!("127.0.0.1:{internal_port}")]);
        guard.clear_temporary_hosts();
        let allowed = client.get(&target_url).send().await.unwrap();
        assert_eq!(allowed.status().as_u16(), 200);
        assert_eq!(allowed.text().await.unwrap(), "ADMIN_HOME");

        // 4. Cloud metadata endpoint stays blocked even if global/rules exist.
        let meta = client
            .get("http://169.254.169.254/latest/meta-data")
            .send()
            .await
            .unwrap();
        assert_eq!(meta.status().as_u16(), 403);

        server_task.abort();
    }

    #[tokio::test]
    async fn custom_upstream_proxy_still_enforces_egress_guard_before_chaining() {
        use std::sync::atomic::{AtomicUsize, Ordering};

        // Start a mock upstream HTTP proxy that records every request it receives.
        let upstream_hits = Arc::new(AtomicUsize::new(0));
        let upstream_hits_srv = upstream_hits.clone();
        let upstream_listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let upstream_port = upstream_listener.local_addr().unwrap().port();
        let upstream_task = tokio::spawn(async move {
            loop {
                let Ok((mut s, _)) = upstream_listener.accept().await else {
                    break;
                };
                let hits = upstream_hits_srv.clone();
                tokio::spawn(async move {
                    hits.fetch_add(1, Ordering::SeqCst);
                    let mut tmp = [0u8; 1024];
                    let _ = s.read(&mut tmp).await;
                    let _ = s
                        .write_all(
                            b"HTTP/1.1 200 OK\r\nContent-Length: 14\r\nConnection: close\r\n\r\nVIA_UPSTREAM_P",
                        )
                        .await;
                });
            }
        });

        let target_listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let target_port = target_listener.local_addr().unwrap().port();
        let guard = EgressGuardHandle::spawn_with_upstream(
            false,
            vec![],
            vec![],
            Some(format!("http://127.0.0.1:{upstream_port}")),
        )
        .await
        .unwrap();

        let proxy = reqwest::Proxy::http(&guard.proxy_url).unwrap();
        let client = reqwest::Client::builder().proxy(proxy).build().unwrap();
        let target_url = format!("http://127.0.0.1:{target_port}/internal");

        // 1. Unauthorized private target MUST be blocked by guard BEFORE touching upstream proxy.
        let blocked = client.get(&target_url).send().await.unwrap();
        assert_eq!(blocked.status().as_u16(), 403);
        assert_eq!(upstream_hits.load(Ordering::SeqCst), 0);

        // 2. Cloud metadata MUST be blocked BEFORE touching upstream proxy.
        let meta = client
            .get("http://169.254.169.254/latest/meta-data")
            .send()
            .await
            .unwrap();
        assert_eq!(meta.status().as_u16(), 403);
        assert_eq!(upstream_hits.load(Ordering::SeqCst), 0);

        // 3. After Admin authorizes target, request chains through the custom upstream proxy!
        guard.set_allowed_private_hosts(vec![format!("127.0.0.1:{target_port}")]);
        let allowed = client.get(&target_url).send().await.unwrap();
        assert_eq!(allowed.status().as_u16(), 200);
        assert_eq!(allowed.text().await.unwrap(), "VIA_UPSTREAM_P");
        assert_eq!(upstream_hits.load(Ordering::SeqCst), 1);

        drop(target_listener);
        upstream_task.abort();
    }
}
