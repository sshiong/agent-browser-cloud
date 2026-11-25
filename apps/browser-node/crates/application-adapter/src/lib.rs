//! Pure-Rust least-privilege Application Adapter runtime for Provider attestations,
//! non-reversible entity identity attributes, and owner-bound Safety Leases.
//!
//! 100% wire-compatible drop-in replacement for `apps/application-adapter/application_adapter.py`.

use anyhow::{anyhow, ensure, Result};
use chrono::Utc;
use hmac::{Hmac, Mac};
use reqwest::header::{HeaderMap, HeaderName, HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE};
use reqwest::{Certificate, Client, Method};
use serde::Serialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashSet};
use std::fs;
use std::path::Path;
use std::time::Duration;

const MAX_SECRET_BYTES: usize = 16 * 1024;
const MAX_RESPONSE_BYTES: usize = 1024 * 1024;
const REFERENCE_HEADERS: &[&str] = &[
    "x-request-id",
    "request-id",
    "x-correlation-id",
    "traceparent",
];

type HmacSha256 = Hmac<Sha256>;

pub fn hex_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(bytes);
    hex_encode(&h.finalize())
}

/// Serializes JSON deterministically with sorted keys and compact separators matching Python's
/// `json.dumps(..., sort_keys=True, separators=(",", ":"))`.
pub fn canonical_json_bytes(value: &Value) -> Vec<u8> {
    if let Value::String(s) = value {
        return s.as_bytes().to_vec();
    }
    let normalized = sort_json(value);
    serde_json::to_vec(&normalized).unwrap_or_default()
}

fn sort_json(v: &Value) -> Value {
    match v {
        Value::Object(map) => {
            let mut btree = BTreeMap::new();
            for (k, val) in map {
                btree.insert(k.clone(), sort_json(val));
            }
            let mut out = serde_json::Map::new();
            for (k, val) in btree {
                out.insert(k, val);
            }
            Value::Object(out)
        }
        Value::Array(arr) => Value::Array(arr.iter().map(sort_json).collect()),
        other => other.clone(),
    }
}

pub fn sha256_value(value: &Value) -> String {
    sha256_hex(&canonical_json_bytes(value))
}

pub fn read_secret_file(path: &Path) -> Result<String> {
    let meta = fs::metadata(path).map_err(|_| anyhow!("SECRET_FILE_UNAVAILABLE"))?;
    ensure!(meta.is_file(), "SECRET_FILE_NOT_REGULAR");
    let size = meta.len() as usize;
    ensure!(
        size > 0 && size <= MAX_SECRET_BYTES,
        "SECRET_FILE_SIZE_INVALID"
    );
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        ensure!(
            (meta.permissions().mode() & 0o077) == 0,
            "SECRET_FILE_PERMISSIONS_TOO_BROAD"
        );
    }
    let raw = fs::read_to_string(path).map_err(|_| anyhow!("SECRET_FILE_UNREADABLE"))?;
    let trimmed = raw.trim();
    ensure!(
        !trimmed.is_empty()
            && !trimmed.contains('\0')
            && !trimmed.contains('\n')
            && !trimmed.contains('\r'),
        "SECRET_VALUE_INVALID"
    );
    Ok(trimmed.to_owned())
}

fn is_valid_entity_component(s: &str) -> bool {
    let bytes = s.as_bytes();
    !bytes.is_empty()
        && bytes.len() <= 128
        && bytes[0].is_ascii_alphabetic()
        && bytes
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'.' | b':' | b'-'))
}

pub fn entity_identity_attributes(
    entity_value: &str,
    namespace: &str,
    entity_type: &str,
    identity_key: &str,
) -> Result<BTreeMap<String, String>> {
    ensure!(
        !entity_value.is_empty()
            && entity_value.len() <= 1024
            && !entity_value.contains('\0')
            && is_valid_entity_component(namespace)
            && is_valid_entity_component(entity_type)
            && identity_key.len() >= 32,
        "ENTITY_IDENTITY_INPUT_INVALID"
    );
    let material = canonical_json_bytes(&json!({
        "entityType": entity_type,
        "entityValue": entity_value,
        "namespace": namespace,
        "version": "entity-identity-v1",
    }));
    let mut mac = HmacSha256::new_from_slice(identity_key.as_bytes())
        .map_err(|_| anyhow!("ENTITY_IDENTITY_INPUT_INVALID"))?;
    mac.update(&material);
    let digest = hex_encode(&mac.finalize().into_bytes());
    let mut attrs = BTreeMap::new();
    attrs.insert("data-agent-entity-hash".to_owned(), digest);
    attrs.insert("data-agent-entity-scope".to_owned(), namespace.to_owned());
    attrs.insert("data-agent-entity-type".to_owned(), entity_type.to_owned());
    Ok(attrs)
}

pub fn extract_json_pointer<'a>(doc: &'a Value, pointer: &str) -> Result<&'a Value> {
    if pointer.is_empty() {
        return Ok(doc);
    }
    ensure!(pointer.starts_with('/'), "PROVIDER_JSON_POINTER_INVALID");
    let mut current = doc;
    for raw_tok in pointer[1..].split('/') {
        let token = raw_tok.replace("~1", "/").replace("~0", "~");
        match current {
            Value::Array(arr) => {
                ensure!(
                    !token.is_empty()
                        && token.bytes().all(|b| b.is_ascii_digit())
                        && !(token.len() > 1 && token.starts_with('0')),
                    "PROVIDER_JSON_POINTER_INVALID"
                );
                let idx: usize = token
                    .parse()
                    .map_err(|_| anyhow!("PROVIDER_JSON_POINTER_INVALID"))?;
                current = arr
                    .get(idx)
                    .ok_or_else(|| anyhow!("PROVIDER_VALUE_MISSING"))?;
            }
            Value::Object(map) => {
                current = map
                    .get(&token)
                    .ok_or_else(|| anyhow!("PROVIDER_VALUE_MISSING"))?;
            }
            _ => return Err(anyhow!("PROVIDER_VALUE_MISSING")),
        }
    }
    Ok(current)
}

pub struct HttpTransport {
    allowed_hosts: HashSet<String>,
    allow_http: bool,
    max_response_bytes: usize,
    http: Client,
}

impl HttpTransport {
    pub fn new(
        allowed_hosts: impl IntoIterator<Item = String>,
        timeout_seconds: f64,
        ca_file: Option<&Path>,
        allow_http: bool,
    ) -> Result<Self> {
        let hosts: HashSet<String> = allowed_hosts
            .into_iter()
            .map(|h| h.trim().trim_end_matches('.').to_ascii_lowercase())
            .filter(|h| !h.is_empty())
            .collect();
        let timeout = Duration::from_secs_f64(timeout_seconds.clamp(0.25, 30.0));
        let mut builder = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(timeout);
        if let Some(ca) = ca_file {
            let pem = fs::read(ca).map_err(|_| anyhow!("CA_FILE_UNAVAILABLE"))?;
            builder = builder.add_root_certificate(Certificate::from_pem(&pem)?);
        }
        Ok(Self {
            allowed_hosts: hosts,
            allow_http,
            max_response_bytes: MAX_RESPONSE_BYTES,
            http: builder.build()?,
        })
    }

    pub async fn request_json(
        &self,
        url: &str,
        method: Method,
        extra_headers: &[(&str, String)],
        payload: Option<Value>,
        error_prefix: &str,
    ) -> Result<(Value, HeaderMap, String)> {
        let uri: http::Uri = url
            .parse()
            .map_err(|_| anyhow!("{error_prefix}_URL_REJECTED"))?;
        let scheme = uri
            .scheme_str()
            .ok_or_else(|| anyhow!("{error_prefix}_URL_REJECTED"))?;
        let scheme_ok = scheme == "https" || (self.allow_http && scheme == "http");
        let authority = uri
            .authority()
            .ok_or_else(|| anyhow!("{error_prefix}_URL_REJECTED"))?;
        let host = authority
            .host()
            .trim_matches(['[', ']'])
            .trim_end_matches('.')
            .to_ascii_lowercase();
        ensure!(
            scheme_ok
                && !host.is_empty()
                && self.allowed_hosts.contains(&host)
                && !authority.as_str().contains('@')
                && !url.contains('#'),
            "{error_prefix}_URL_REJECTED"
        );

        let mut headers = HeaderMap::new();
        headers.insert(ACCEPT, HeaderValue::from_static("application/json"));
        for (k, v) in extra_headers {
            let name = HeaderName::from_bytes(k.as_bytes())?;
            let val = HeaderValue::from_str(v)?;
            headers.insert(name, val);
        }

        let mut req = self.http.request(method, url).headers(headers);
        if let Some(body) = payload {
            let encoded = serde_json::to_vec(&body)?;
            req = req.header(CONTENT_TYPE, "application/json").body(encoded);
        }
        let resp = req
            .send()
            .await
            .map_err(|_| anyhow!("{error_prefix}_UNAVAILABLE"))?;
        let status = resp.status();
        if status.is_client_error() || status.is_server_error() {
            return Err(anyhow!("{error_prefix}_HTTP_{}", status.as_u16()));
        }
        let resp_headers = resp.headers().clone();
        let ct = resp_headers
            .get(CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .split(';')
            .next()
            .unwrap_or("")
            .trim()
            .to_ascii_lowercase();
        ensure!(
            ct == "application/json" || ct.ends_with("+json"),
            "{error_prefix}_CONTENT_TYPE_INVALID"
        );
        let raw = resp
            .bytes()
            .await
            .map_err(|_| anyhow!("{error_prefix}_UNAVAILABLE"))?;
        ensure!(
            raw.len() <= self.max_response_bytes,
            "{error_prefix}_RESPONSE_TOO_LARGE"
        );
        let doc: Value =
            serde_json::from_slice(&raw).map_err(|_| anyhow!("{error_prefix}_JSON_INVALID"))?;
        ensure!(
            doc.is_object() || doc.is_array(),
            "{error_prefix}_JSON_ROOT_INVALID"
        );
        let hash = sha256_hex(&raw);
        Ok((doc, resp_headers, hash))
    }
}

#[derive(Debug, Clone)]
pub struct ProviderAttestationSpec {
    pub session_id: String,
    pub context_epoch: i64,
    pub state_version: i64,
    pub evidence_type: String,
    pub key: String,
    pub provider_id: String,
    pub expected_value_hash: String,
    pub provider_url: String,
    pub value_pointer: String,
    pub reference_header: String,
}

impl ProviderAttestationSpec {
    pub fn validate(&self) -> Result<()> {
        let ses_rest = self
            .session_id
            .strip_prefix("ses_")
            .ok_or_else(|| anyhow!("SESSION_ID_INVALID"))?;
        ensure!(
            ses_rest.len() >= 16 && ses_rest.bytes().all(|b| b.is_ascii_alphanumeric()),
            "SESSION_ID_INVALID"
        );
        ensure!(
            self.context_epoch >= 1 && self.state_version >= 1,
            "SESSION_VERSION_INVALID"
        );
        ensure!(
            matches!(
                self.evidence_type.as_str(),
                "ACCOUNT" | "TENANT_WORKSPACE" | "PERMISSION" | "BUSINESS_ENTITY"
            ),
            "EVIDENCE_TYPE_INVALID"
        );
        ensure!(
            self.expected_value_hash.len() == 64
                && self
                    .expected_value_hash
                    .bytes()
                    .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')),
            "EXPECTED_VALUE_HASH_INVALID"
        );
        let valid_ident = |s: &str| {
            let b = s.as_bytes();
            !b.is_empty()
                && b.len() <= 128
                && b[0].is_ascii_alphabetic()
                && b.iter()
                    .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'.' | b'-'))
        };
        ensure!(
            valid_ident(&self.key) && valid_ident(&self.provider_id),
            "EVIDENCE_IDENTIFIER_INVALID"
        );
        ensure!(
            REFERENCE_HEADERS.contains(&self.reference_header.to_ascii_lowercase().as_str()),
            "PROVIDER_REFERENCE_HEADER_REJECTED"
        );
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct AttestationReceipt {
    pub evidence_id: String,
    pub outcome: String,
    pub request_id: String,
    pub expires_at: String,
}

pub struct BrowserCloudAdapterClient {
    base_url: String,
    transport: HttpTransport,
    bearer_token: String,
    local_identity: Option<(String, String)>,
}

impl BrowserCloudAdapterClient {
    pub fn new(
        base_url: String,
        transport: HttpTransport,
        bearer_token: String,
        local_identity: Option<(String, String)>,
    ) -> Self {
        Self {
            base_url: base_url.trim_end_matches('/').to_owned(),
            transport,
            bearer_token,
            local_identity,
        }
    }

    async fn request(
        &self,
        path: &str,
        method: Method,
        idempotency_key: &str,
        payload: Option<Value>,
    ) -> Result<Value> {
        let mut headers = vec![
            (
                AUTHORIZATION.as_str(),
                format!("Bearer {}", self.bearer_token),
            ),
            ("idempotency-key", idempotency_key.to_owned()),
        ];
        if let Some((tenant, actor)) = &self.local_identity {
            headers.push(("x-tenant-id", tenant.clone()));
            headers.push(("x-actor-id", actor.clone()));
            headers.push(("x-roles", "APPLICATION_ADAPTER".to_owned()));
        }
        let (doc, _, _) = self
            .transport
            .request_json(
                &format!("{}{path}", self.base_url),
                method,
                &headers,
                payload,
                "CONTROL_PLANE",
            )
            .await?;
        ensure!(doc.is_object(), "CONTROL_PLANE_JSON_ROOT_INVALID");
        Ok(doc)
    }

    pub async fn run_attestation(
        &self,
        provider_transport: &HttpTransport,
        provider_token: Option<&str>,
        spec: &ProviderAttestationSpec,
    ) -> Result<AttestationReceipt> {
        spec.validate()?;
        let mut p_headers = Vec::new();
        if let Some(tok) = provider_token {
            p_headers.push((AUTHORIZATION.as_str(), format!("Bearer {tok}")));
        }
        let (doc, resp_headers, resp_hash) = provider_transport
            .request_json(
                &spec.provider_url,
                Method::GET,
                &p_headers,
                None,
                "PROVIDER",
            )
            .await?;
        let selected = extract_json_pointer(&doc, &spec.value_pointer)?;
        let observed_hash = sha256_value(selected);
        let outcome = if observed_hash == spec.expected_value_hash {
            "MATCH"
        } else {
            "MISMATCH"
        };
        let raw_ref = resp_headers
            .get(spec.reference_header.as_str())
            .and_then(|v| v.to_str().ok())
            .map(str::trim)
            .filter(|s| !s.is_empty());
        let reference = match raw_ref {
            Some(r) => r.chars().take(512).collect::<String>(),
            None => format!("response-sha256:{resp_hash}"),
        };
        let observed_at = Utc::now().to_rfc3339();
        let idem_mat = canonical_json_bytes(&json!({
            "sessionId": spec.session_id,
            "contextEpoch": spec.context_epoch,
            "stateVersion": spec.state_version,
            "type": spec.evidence_type,
            "key": spec.key,
            "providerId": spec.provider_id,
            "observedValueHash": observed_hash,
            "outcome": outcome,
            "observedAt": observed_at,
            "providerReferenceHash": sha256_hex(reference.as_bytes()),
        }));
        let idempotency_key = format!("att-{}", sha256_hex(&idem_mat));

        let res = self
            .request(
                &format!(
                    "/api/v1/sessions/{}/business-recovery/provider-evidence",
                    spec.session_id
                ),
                Method::POST,
                &idempotency_key,
                Some(json!({
                    "contextEpoch": spec.context_epoch,
                    "stateVersion": spec.state_version,
                    "type": spec.evidence_type,
                    "key": spec.key,
                    "providerId": spec.provider_id,
                    "observedValueHash": observed_hash,
                    "outcome": outcome,
                    "providerReference": reference,
                    "observedAt": observed_at,
                })),
            )
            .await?;
        Ok(AttestationReceipt {
            evidence_id: res
                .get("evidenceId")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned(),
            outcome: res
                .get("outcome")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned(),
            request_id: res
                .get("requestId")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned(),
            expires_at: res
                .get("expiresAt")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned(),
        })
    }

    pub async fn acquire_lease(
        &self,
        session_id: &str,
        signal_type: &str,
        reason_code: &str,
        ttl_seconds: i64,
        idempotency_key: &str,
    ) -> Result<Value> {
        self.request(
            &format!("/api/v1/sessions/{session_id}/safety-leases"),
            Method::POST,
            idempotency_key,
            Some(json!({
                "signalType": signal_type,
                "reasonCode": reason_code,
                "ttlSeconds": ttl_seconds,
            })),
        )
        .await
    }

    pub async fn renew_lease(
        &self,
        session_id: &str,
        lease_id: &str,
        ttl_seconds: i64,
        idempotency_key: &str,
    ) -> Result<Value> {
        self.request(
            &format!("/api/v1/sessions/{session_id}/safety-leases/{lease_id}"),
            Method::PUT,
            idempotency_key,
            Some(json!({ "ttlSeconds": ttl_seconds })),
        )
        .await
    }

    pub async fn release_lease(
        &self,
        session_id: &str,
        lease_id: &str,
        idempotency_key: &str,
    ) -> Result<Value> {
        self.request(
            &format!("/api/v1/sessions/{session_id}/safety-leases/{lease_id}:release"),
            Method::POST,
            idempotency_key,
            None,
        )
        .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::routing::{get, post};
    use axum::{Json, Router};

    #[tokio::test]
    async fn attest_hashes_locally_without_sending_provider_plaintext_to_control_plane() {
        let app = Router::new()
            .route(
                "/provider/account",
                get(|| async {
                    (
                        [("x-request-id", "prov-req-42")],
                        Json(json!({ "account": { "id": "acct-secret-999" } })),
                    )
                }),
            )
            .route(
                "/api/v1/sessions/ses_1234567890abcdef/business-recovery/provider-evidence",
                post(|Json(body): Json<Value>| async move {
                    // Plaintext must NEVER leave the adapter!
                    assert!(!body.to_string().contains("acct-secret-999"));
                    assert_eq!(body["outcome"], "MATCH");
                    assert_eq!(body["providerReference"], "prov-req-42");
                    Json(json!({
                        "evidenceId": "ev_001",
                        "outcome": "MATCH",
                        "requestId": "req_001",
                        "expiresAt": "2026-11-26T00:00:00Z"
                    }))
                }),
            )
            .route(
                "/api/v1/sessions/ses_1234567890abcdef/safety-leases",
                post(|Json(body): Json<Value>| async move {
                    assert_eq!(body["signalType"], "PAYMENT_OR_SECURITY");
                    Json(json!({ "leaseId": "lse_001", "state": "ACTIVE" }))
                }),
            );

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let srv = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });

        let expected = sha256_value(&json!("acct-secret-999"));
        let p_transport = HttpTransport::new(["127.0.0.1".to_owned()], 5.0, None, true).unwrap();
        let cp_transport = HttpTransport::new(["127.0.0.1".to_owned()], 5.0, None, true).unwrap();
        let client = BrowserCloudAdapterClient::new(
            format!("http://{addr}"),
            cp_transport,
            "cp-token".into(),
            Some(("tenant-a".into(), "adapter-1".into())),
        );
        let spec = ProviderAttestationSpec {
            session_id: "ses_1234567890abcdef".into(),
            context_epoch: 1,
            state_version: 2,
            evidence_type: "ACCOUNT".into(),
            key: "primary".into(),
            provider_id: "billing".into(),
            expected_value_hash: expected,
            provider_url: format!("http://{addr}/provider/account"),
            value_pointer: "/account/id".into(),
            reference_header: "x-request-id".into(),
        };
        let receipt = client
            .run_attestation(&p_transport, Some("prov-tok"), &spec)
            .await
            .unwrap();
        assert_eq!(receipt.evidence_id, "ev_001");
        assert_eq!(receipt.outcome, "MATCH");

        let lease = client
            .acquire_lease(
                "ses_1234567890abcdef",
                "PAYMENT_OR_SECURITY",
                "CHECKOUT",
                120,
                "idem-lease-1",
            )
            .await
            .unwrap();
        assert_eq!(lease["leaseId"], "lse_001");

        let attrs = entity_identity_attributes(
            "order-99",
            "billing",
            "order",
            "0123456789abcdef0123456789abcdef",
        )
        .unwrap();
        assert_eq!(attrs["data-agent-entity-hash"].len(), 64);

        srv.abort();
    }
}
