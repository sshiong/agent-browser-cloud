//! Static, URL-free navigation diagnostics for local Node logs.

#[derive(Debug)]
pub(crate) struct NavigationFailure(pub(crate) &'static str);

impl std::fmt::Display for NavigationFailure {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.0)
    }
}

impl std::error::Error for NavigationFailure {}

/// Never returns the original CDP/transport error, which may contain a URL or page text.
pub fn navigation_failure_reason(error: &anyhow::Error) -> &'static str {
    error
        .downcast_ref::<NavigationFailure>()
        .map_or("UNKNOWN", |failure| failure.0)
}

pub(crate) fn network_failure(error_text: &str) -> NavigationFailure {
    let code = match error_text {
        "net::ERR_ABORTED" => "NET_ABORTED",
        "net::ERR_BLOCKED_BY_CLIENT" => "NET_BLOCKED_BY_CLIENT",
        "net::ERR_CERT_AUTHORITY_INVALID" => "NET_CERT_AUTHORITY_INVALID",
        "net::ERR_CERT_DATE_INVALID" => "NET_CERT_DATE_INVALID",
        "net::ERR_CERT_COMMON_NAME_INVALID" => "NET_CERT_COMMON_NAME_INVALID",
        "net::ERR_CONNECTION_CLOSED" => "NET_CONNECTION_CLOSED",
        "net::ERR_CONNECTION_REFUSED" => "NET_CONNECTION_REFUSED",
        "net::ERR_CONNECTION_RESET" => "NET_CONNECTION_RESET",
        "net::ERR_CONNECTION_TIMED_OUT" => "NET_CONNECTION_TIMED_OUT",
        "net::ERR_EMPTY_RESPONSE" => "NET_EMPTY_RESPONSE",
        "net::ERR_FAILED" => "NET_FAILED",
        "net::ERR_HTTP_RESPONSE_CODE_FAILURE" => "NET_HTTP_RESPONSE_CODE_FAILURE",
        "net::ERR_NAME_NOT_RESOLVED" => "NET_NAME_NOT_RESOLVED",
        "net::ERR_NETWORK_CHANGED" => "NET_NETWORK_CHANGED",
        "net::ERR_TIMED_OUT" => "NET_TIMED_OUT",
        "net::ERR_TOO_MANY_REDIRECTS" => "NET_TOO_MANY_REDIRECTS",
        "net::ERR_TUNNEL_CONNECTION_FAILED" => "NET_TUNNEL_CONNECTION_FAILED",
        _ => "NETWORK_ERROR_OTHER",
    };
    NavigationFailure(code)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn typed_reason_survives_context_without_returning_source_text() {
        let error = anyhow::anyhow!("https://private.invalid/?code=private-code")
            .context(NavigationFailure("WEBSOCKET_TRANSPORT_ERROR"))
            .context("outer context with private-token");
        assert_eq!(
            navigation_failure_reason(&error),
            "WEBSOCKET_TRANSPORT_ERROR"
        );
        assert_eq!(
            navigation_failure_reason(&anyhow::anyhow!("private-token")),
            "UNKNOWN"
        );
    }

    #[test]
    fn network_reason_requires_exact_known_code() {
        assert_eq!(
            network_failure("net::ERR_EMPTY_RESPONSE").0,
            "NET_EMPTY_RESPONSE"
        );
        assert_eq!(network_failure("net::ERR_TIMED_OUT").0, "NET_TIMED_OUT");
        for text in [
            "net::ERR_EMPTY_RESPONSE https://private.invalid/?code=private-code",
            "net::ERR_PRIVATE_TOKEN",
            "private-token",
        ] {
            assert_eq!(network_failure(text).0, "NETWORK_ERROR_OTHER");
        }
    }
}
