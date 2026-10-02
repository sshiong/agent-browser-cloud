"""Ephemeral relying party for the official Duende demo; never saves tokens or codes."""

import base64
import hashlib
import json
import pathlib
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ISSUER = "https://demo.duendesoftware.com"
CLIENT_ID = "interactive.public"
CALLBACK = "https://agent-controls.invalid/oidc-callback"
RESULT = "https://agent-controls.invalid/oidc-result"
ENDPOINTS = {
    "issuer": ISSUER,
    "authorization_endpoint": ISSUER + "/connect/authorize",
    "token_endpoint": ISSUER + "/connect/token",
    "jwks_uri": ISSUER + "/.well-known/openid-configuration/jwks",
    "userinfo_endpoint": ISSUER + "/connect/userinfo",
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def provider_request(url, fields=None, access_token=None):
    if url not in set(ENDPOINTS.values()) | {ISSUER + "/.well-known/openid-configuration"}:
        raise ValueError("OIDC_ENDPOINT_REJECTED")
    data = None if fields is None else urllib.parse.urlencode(fields).encode()
    headers = {"Accept": "application/json"}
    if access_token:
        headers["Authorization"] = "Bearer " + access_token
    request = urllib.request.Request(url, data=data, headers=headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        response = opener.open(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read(65537)
        if len(body) > 65536 or response.headers.get_content_type() != "application/json":
            raise ValueError("OIDC_RESPONSE_REJECTED")
        return response.status, json.loads(body)


class PublicOidcClient:
    def __init__(self):
        self.lock = threading.Lock()
        self.flow = None
        self.started = False
        self.proof = None

    def begin(self):
        with self.lock:
            if self.started:
                raise ValueError("OIDC_FLOW_ALREADY_STARTED")
            self.started = True
        status, metadata = provider_request(ISSUER + "/.well-known/openid-configuration")
        if status != 200 or any(metadata.get(key) != value for key, value in ENDPOINTS.items()):
            raise ValueError("OIDC_DISCOVERY_REJECTED")
        if "form_post" not in metadata.get("response_modes_supported", []):
            raise ValueError("OIDC_RESPONSE_MODE_REJECTED")
        flow = {key: secrets.token_urlsafe(32) for key in ("state", "nonce", "verifier")}
        flow["expires"] = time.monotonic() + 300
        challenge = base64.urlsafe_b64encode(hashlib.sha256(flow["verifier"].encode()).digest()).rstrip(b"=").decode()
        with self.lock:
            self.flow = flow
        return ENDPOINTS["authorization_endpoint"] + "?" + urllib.parse.urlencode({
            "client_id": CLIENT_ID, "redirect_uri": CALLBACK, "response_type": "code",
            "scope": "openid profile", "response_mode": "form_post",
            "state": flow["state"], "nonce": flow["nonce"],
            "code_challenge": challenge, "code_challenge_method": "S256",
        })

    def complete(self, body):
        if len(body) > 8192:
            raise ValueError("OIDC_CALLBACK_REJECTED")
        fields = urllib.parse.parse_qs(body.decode("utf-8"), keep_blank_values=True, max_num_fields=8)
        with self.lock:
            flow = self.flow
            if (not flow or time.monotonic() >= flow["expires"]
                or any(len(values) != 1 for values in fields.values())
                or fields.get("error") or not fields.get("code", [""])[0]
                or not secrets.compare_digest(fields.get("state", [""])[0], flow["state"])
                or fields.get("iss", [ISSUER])[0] != ISSUER):
                raise ValueError("OIDC_CALLBACK_REJECTED")
            self.flow = None  # Consume before the first provider call; callbacks cannot be replayed.
        exchange = {
            "grant_type": "authorization_code", "client_id": CLIENT_ID,
            "code": fields["code"][0], "redirect_uri": CALLBACK,
            "code_verifier": flow["verifier"],
        }
        status, tokens = provider_request(ENDPOINTS["token_endpoint"], exchange)
        if (status != 200 or tokens.get("token_type", "").lower() != "bearer"
            or not tokens.get("access_token") or not tokens.get("id_token") or tokens.get("refresh_token")):
            raise ValueError("OIDC_TOKEN_REJECTED")
        key_status, jwks = provider_request(ENDPOINTS["jwks_uri"])
        user_status, userinfo = provider_request(ENDPOINTS["userinfo_endpoint"], access_token=tokens["access_token"])
        if key_status != 200 or user_status != 200:
            raise ValueError("OIDC_IDENTITY_REJECTED")
        checked = subprocess.run(
            ["node", str(pathlib.Path(__file__).with_name("verify-public-oidc.mjs"))],
            input=json.dumps({"token": tokens["id_token"], "jwks": jwks,
                              "nonce": flow["nonce"], "userinfo": userinfo}),
            text=True, capture_output=True, timeout=10, check=False,
        )
        if checked.returncode != 0:
            raise ValueError("OIDC_PROOF_REJECTED")
        proof = json.loads(checked.stdout)
        if proof != {"signature": True, "issuerAudienceNonce": True, "userinfoSubject": True}:
            raise ValueError("OIDC_PROOF_REJECTED")
        replay_status, replay = provider_request(ENDPOINTS["token_endpoint"], exchange)
        if replay_status != 400 or replay.get("error") != "invalid_grant":
            raise ValueError("OIDC_CODE_REPLAY_NOT_REJECTED")
        with self.lock:
            self.proof = {**proof, "pkce": True, "reusedCodeRejected": True}
        return RESULT
