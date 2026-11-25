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
use anyhow::Result;
use reqwest::Url;
use std::net::SocketAddr;
use std::sync::{Arc, RwLock};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{lookup_host, TcpListener, TcpStream};
use tokio::task::AbortHandle;

pub struct EgressGuardHandle {
    pub proxy_url: String,
    allowed_private_hosts: Arc<RwLock<Vec<String>>>,
    abort_handle: AbortHandle,
}

impl EgressGuardHandle {
    pub async fn spawn(
        global_allow_private: bool,
        initial_allowed_private_hosts: Vec<String>,
    ) -> Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
        let port = listener.local_addr()?.port();
        let allowed_private_hosts = Arc::new(RwLock::new(initial_allowed_private_hosts));
        let rules_ref = allowed_private_hosts.clone();

        let task = tokio::spawn(async move {
            loop {
                let Ok((stream, _peer)) = listener.accept().await else {
                    break;
                };
                let rules = rules_ref.clone();
                tokio::spawn(async move {
                    let _ = handle_client(stream, global_allow_private, rules).await;
                });
            }
        });

        Ok(Self {
            proxy_url: format!("http://127.0.0.1:{port}"),
            allowed_private_hosts,
            abort_handle: task.abort_handle(),
        })
    }

    pub fn set_allowed_private_hosts(&self, hosts: Vec<String>) {
        if let Ok(mut guard) = self.allowed_private_hosts.write() {
            *guard = hosts;
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
    allowed_rules: Arc<RwLock<Vec<String>>>,
) -> Result<()> {
    let mut buf = Vec::with_capacity(4096);
    let mut chunk = [0u8; 2048];
    let header_end = loop {
        let n = client.read(&mut chunk).await?;
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

    let rules_snapshot = allowed_rules.read().map(|g| g.clone()).unwrap_or_default();

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
        let mut upstream = match TcpStream::connect(upstream_addr).await {
            Ok(s) => s,
            Err(_) => {
                write_error(&mut client, 502, "UPSTREAM_CONNECT_FAILED").await;
                return Ok(());
            }
        };
        client
            .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            .await?;
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

    let mut upstream = match TcpStream::connect(upstream_addr).await {
        Ok(s) => s,
        Err(_) => {
            write_error(&mut client, 502, "UPSTREAM_CONNECT_FAILED").await;
            return Ok(());
        }
    };

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
        let lower = line.to_ascii_lowercase();
        if lower.starts_with("proxy-connection:") || lower.starts_with("proxy-authorization:") {
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
    let _ = tokio::io::copy_bidirectional(&mut client, &mut upstream).await;
    Ok(())
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

fn find_header_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

async fn write_error(stream: &mut TcpStream, status: u16, reason: &str) {
    let status_text = match status {
        400 => "Bad Request",
        403 => "Forbidden",
        431 => "Request Header Fields Too Large",
        502 => "Bad Gateway",
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

        let guard = EgressGuardHandle::spawn(false, vec![]).await.unwrap();
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

        // 2. Authorize this internal host dynamically in environment config — NO browser restart!
        guard.set_allowed_private_hosts(vec![format!("127.0.0.1:{internal_port}")]);
        let allowed = client.get(&target_url).send().await.unwrap();
        assert_eq!(allowed.status().as_u16(), 200);
        assert_eq!(allowed.text().await.unwrap(), "ADMIN_HOME");

        // 3. Cloud metadata endpoint stays blocked even if global/rules exist.
        let meta = client
            .get("http://169.254.169.254/latest/meta-data")
            .send()
            .await
            .unwrap();
        assert_eq!(meta.status().as_u16(), 403);

        server_task.abort();
    }
}
