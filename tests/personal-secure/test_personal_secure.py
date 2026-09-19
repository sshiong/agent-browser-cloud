import base64
import json
import os
import pathlib
import shutil
import stat
import subprocess
import tempfile
import time
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
DEPLOYMENT = ROOT / "deploy" / "personal-secure"


def jwt(
    role: str,
    audience: str = "browsercloud-api",
    issuer: str = "https://identity.example.test/realms/personal",
    expires_in_seconds: int = 7200,
) -> str:
    def encode(value: dict) -> str:
        raw = json.dumps(value, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")

    payload = {
        "aud": audience,
        "exp": int(time.time()) + expires_in_seconds,
        "iss": issuer,
        "roles": [role],
    }
    return f'{encode({"alg":"RS256","typ":"JWT"})}.{encode(payload)}.signature'


class PersonalSecureDeploymentTest(unittest.TestCase):
    def test_control_plane_entrypoint_resolves_file_secrets_and_drops_file_paths(self):
        with tempfile.TemporaryDirectory() as root_value:
            root = pathlib.Path(root_value)
            fake_bin = root / "bin"
            fake_bin.mkdir()
            fake_java = fake_bin / "java"
            fake_java.write_text("#!/bin/sh\nenv\n")
            fake_java.chmod(0o755)
            environment = {"PATH": f"{fake_bin}:/usr/bin:/bin"}
            for name in (
                "DATABASE_PASSWORD",
                "REDIS_PASSWORD",
                "REMOTE_DESKTOP_TICKET_SECRET",
                "AGENT_CAPABILITY_TOKEN_SECRET",
                "AGENT_ACTION_PAYLOAD_SECRET",
                "AUDIT_EXPORT_SIGNING_KEY",
            ):
                secret = root / name.lower()
                secret.write_text(f"{name.lower()}-value\n")
                environment[f"{name}_FILE"] = str(secret)
            result = subprocess.run(
                [str(ROOT / "apps" / "control-plane" / "docker-entrypoint.sh")],
                env=environment,
                text=True,
                check=True,
                capture_output=True,
            )
            for name in (
                "DATABASE_PASSWORD",
                "REDIS_PASSWORD",
                "REMOTE_DESKTOP_TICKET_SECRET",
                "AGENT_CAPABILITY_TOKEN_SECRET",
                "AGENT_ACTION_PAYLOAD_SECRET",
                "AUDIT_EXPORT_SIGNING_KEY",
            ):
                self.assertIn(f"{name}={name.lower()}-value", result.stdout)
                self.assertNotIn(f"{name}_FILE=", result.stdout)

    def test_compose_is_loopback_only_and_runs_the_real_worker_chain(self):
        compose = (DEPLOYMENT / "compose.yaml").read_text()
        self.assertIn("127.0.0.1:${PERSONAL_PORT:-3000}:8080", compose)
        self.assertEqual(compose.count("ports:"), 1)
        self.assertIn("APP_ENVIRONMENT: personal", compose)
        self.assertIn('GRPC_TLS_ENABLED: "true"', compose)
        self.assertIn('ALLOW_DIRECT_NETWORK: "false"', compose)
        self.assertIn('PROXY_ALLOW_DIRECT: "false"', compose)
        self.assertIn("internal: true", compose)
        for service in (
            "agent-worker:",
            "reviewer-worker:",
            "outcome-verifier-worker:",
            "vision-worker:",
        ):
            self.assertIn(service, compose)
        for unsafe in (
            "VITE_AUTH_MODE: local",
            "browsercloud-local-remote-desktop-ticket-secret-v1",
            "POSTGRES_PASSWORD: browsercloud",
            "privileged: true",
        ):
            self.assertNotIn(unsafe, compose)

    def test_web_image_accepts_production_oidc_build_inputs(self):
        dockerfile = (ROOT / "apps" / "web-console" / "Dockerfile").read_text()
        for name in (
            "VITE_OIDC_AUTHORITY",
            "VITE_OIDC_CLIENT_ID",
            "VITE_OIDC_REDIRECT_URI",
            "VITE_OIDC_ROLES_CLAIM",
            "VITE_OIDC_TENANT_CLAIM",
        ):
            self.assertIn(f"ARG {name}", dockerfile)
            self.assertIn(f"ENV {name}=${{{name}}}", dockerfile)
        nginx = (ROOT / "apps" / "web-console" / "nginx.conf").read_text()
        self.assertIn("connect-src 'self' ${OIDC_CONNECT_SRC}", nginx)
        self.assertIn("envsubst '${OIDC_CONNECT_SRC}'", dockerfile)
        self.assertIn("> /etc/nginx/conf.d/default.conf", dockerfile)

    def test_init_generates_distinct_secrets_mtls_and_role_bound_worker_tokens(self):
        with tempfile.TemporaryDirectory() as root_value:
            root = pathlib.Path(root_value)
            deployment = root / "personal-secure"
            shutil.copytree(DEPLOYMENT, deployment)
            sources = root / "sources"
            sources.mkdir()
            (sources / "object-secret").write_text("object-secret-value\n")
            (sources / "model-key").write_text("model-secret-value\n")
            roles = {
                "agent": "AGENT_WORKER",
                "reviewer": "REVIEWER_WORKER",
                "outcome": "OUTCOME_VERIFIER_WORKER",
                "vision": "VISION_WORKER",
            }
            for name, role in roles.items():
                (sources / f"{name}.jwt").write_text(jwt(role) + "\n")
            settings = {
                "PERSONAL_PORT": "3000",
                "PERSONAL_OIDC_ISSUER": "https://identity.example.test/realms/personal",
                "PERSONAL_OIDC_CLIENT_ID": "browsercloud-web",
                "PERSONAL_OIDC_AUDIENCE": "browsercloud-api",
                "PERSONAL_OIDC_ROLES_CLAIM": "roles",
                "PERSONAL_PROXY_EXPECTED_EXIT_IP": "203.0.113.10",
                "PERSONAL_PROXY_EXIT_CHECK_URL": "https://api.ipify.org",
                "PERSONAL_OBJECT_STORAGE_ENDPOINT": "https://s3.example.test",
                "PERSONAL_OBJECT_STORAGE_HOST": "s3.example.test",
                "PERSONAL_OBJECT_STORAGE_BUCKET": "browsercloud-personal",
                "PERSONAL_OBJECT_STORAGE_ACCESS_KEY_ID": "access-id",
                "PERSONAL_MODEL_ENDPOINT": "https://models.example.test/v1/responses",
                "PERSONAL_MODEL_HOST": "models.example.test",
                "PERSONAL_MODEL_NAME": "reviewer-model",
                "PERSONAL_OBJECT_STORAGE_SECRET_ACCESS_KEY_FILE": str(sources / "object-secret"),
                "PERSONAL_MODEL_API_KEY_FILE": str(sources / "model-key"),
                "PERSONAL_AGENT_WORKER_TOKEN_FILE": str(sources / "agent.jwt"),
                "PERSONAL_REVIEWER_WORKER_TOKEN_FILE": str(sources / "reviewer.jwt"),
                "PERSONAL_OUTCOME_VERIFIER_WORKER_TOKEN_FILE": str(sources / "outcome.jwt"),
                "PERSONAL_VISION_WORKER_TOKEN_FILE": str(sources / "vision.jwt"),
            }
            (deployment / ".env").write_text(
                "\n".join(f"{key}={value}" for key, value in settings.items()) + "\n"
            )
            script = deployment / "personal-secure"
            script.chmod(0o755)
            environment = os.environ.copy()
            environment["PERSONAL_SECURE_OFFLINE_CHECK"] = "1"
            subprocess.run([str(script), "init"], check=True, env=environment, capture_output=True)

            secret_root = deployment / ".state" / "bootstrap-secrets"
            postgres = (secret_root / "postgres" / "password").read_text().strip()
            redis = (secret_root / "redis" / "password").read_text().strip()
            self.assertGreaterEqual(len(postgres), 48)
            self.assertNotEqual(postgres, redis)
            self.assertNotEqual(
                (secret_root / "control-plane" / "agent-capability-token").read_text(),
                (secret_root / "control-plane" / "agent-action-payload").read_text(),
            )
            self.assertEqual(
                stat.S_IMODE((secret_root / "agent-worker" / "token").stat().st_mode), 0o600
            )
            certificate = subprocess.run(
                [
                    "openssl",
                    "x509",
                    "-in",
                    str(deployment / ".state" / "pki" / "worker-gateway.crt"),
                    "-noout",
                    "-ext",
                    "subjectAltName",
                ],
                check=True,
                text=True,
                capture_output=True,
            ).stdout
            self.assertIn("DNS:worker-gateway", certificate)
            generated_env = (deployment / ".env").read_text()
            self.assertIn(f"PERSONAL_STATE_DIR={deployment / '.state'}", generated_env)
            if shutil.which("docker"):
                compose_available = subprocess.run(
                    ["docker", "compose", "version"], capture_output=True
                ).returncode == 0
                if compose_available:
                    result = subprocess.run(
                        [str(script), "check"],
                        env=environment,
                        text=True,
                        capture_output=True,
                    )
                    self.assertEqual(result.returncode, 0, result.stderr)
            (secret_root / "agent-worker" / "token").write_text(jwt("REVIEWER_WORKER") + "\n")
            rejected = subprocess.run(
                [str(script), "check"],
                env=environment,
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("must contain only AGENT_WORKER", rejected.stderr)
            (secret_root / "agent-worker" / "token").write_text(
                jwt("AGENT_WORKER", expires_in_seconds=3599) + "\n"
            )
            expired = subprocess.run(
                [str(script), "check"],
                env=environment,
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(expired.returncode, 0)
            self.assertIn("must remain valid for at least one hour", expired.stderr)


if __name__ == "__main__":
    unittest.main()
