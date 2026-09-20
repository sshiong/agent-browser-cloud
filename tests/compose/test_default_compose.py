import os
import pathlib
import stat
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "docker-compose.yml"
PREFLIGHT = ROOT / "tools/local-dev/check-agent-compose.sh"


class DefaultComposeTest(unittest.TestCase):
    def environment(self, key_file: pathlib.Path) -> dict[str, str]:
        return {
            **os.environ,
            "LOCAL_AGENT_MODEL_API_KEY_FILE": str(key_file),
            "LOCAL_AGENT_MODEL_ENDPOINT": "https://models.example.test/v1",
            "LOCAL_AGENT_MODEL_NAME": "real-model-deployment",
        }

    def test_compose_contains_complete_external_worker_chain(self):
        compose = COMPOSE.read_text()
        for service in (
            "agent-worker:",
            "reviewer-worker:",
            "outcome-verifier-worker:",
            "vision-worker:",
        ):
            self.assertIn(service, compose)
        for setting in (
            'AGENT_EXTERNAL_WORKER_ENABLED: "true"',
            'AGENT_REVIEWER_EXTERNAL_ENABLED: "true"',
            'AGENT_OUTCOME_VERIFIER_EXTERNAL_ENABLED: "true"',
        ):
            self.assertIn(setting, compose)
        self.assertEqual(compose.count("--ready-file, /tmp/worker-ready"), 4)
        self.assertEqual(compose.count("--expected-response-model"), 3)
        self.assertEqual(compose.count("--model-timeout-seconds"), 3)
        self.assertEqual(compose.count("--maximum-output-tokens"), 3)
        self.assertEqual(compose.count("network_mode: service:control-plane"), 1)
        self.assertNotIn("model-fixture", compose.lower())
        self.assertIn("${LOCAL_AGENT_MODEL_API_KEY_FILE", compose)

    def test_preflight_requires_real_private_model_credential_and_valid_compose(self):
        with tempfile.TemporaryDirectory() as directory:
            key_file = pathlib.Path(directory) / "model-api-key"
            key_file.write_text("real-secret-from-operator\n")
            key_file.chmod(0o600)
            subprocess.run(
                [str(PREFLIGHT)],
                cwd=ROOT,
                env=self.environment(key_file),
                check=True,
                capture_output=True,
                text=True,
            )

            key_file.chmod(0o644)
            rejected = subprocess.run(
                [str(PREFLIGHT)],
                cwd=ROOT,
                env=self.environment(key_file),
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("0600 or 0400", rejected.stderr)

    def test_preflight_accepts_http_or_https_and_rejects_invalid_model_configuration(self):
        missing = subprocess.run(
            [str(PREFLIGHT)], cwd=ROOT, env={"PATH": os.environ["PATH"]}, capture_output=True, text=True
        )
        self.assertNotEqual(missing.returncode, 0)
        self.assertIn("LOCAL_AGENT_MODEL_ENDPOINT is required", missing.stderr)

        with tempfile.TemporaryDirectory() as directory:
            key_file = pathlib.Path(directory) / "model-api-key"
            key_file.write_text("secret\n")
            key_file.chmod(stat.S_IRUSR | stat.S_IWUSR)
            environment = self.environment(key_file)
            environment["LOCAL_AGENT_MODEL_ENDPOINT"] = "http://models.example.test/v1"
            accepted = subprocess.run(
                [str(PREFLIGHT)], cwd=ROOT, env=environment, capture_output=True, text=True
            )
            self.assertEqual(accepted.returncode, 0, accepted.stderr)

            environment["LOCAL_AGENT_MODEL_ENDPOINT"] = "ftp://models.example.test/v1/responses"
            rejected = subprocess.run(
                [str(PREFLIGHT)], cwd=ROOT, env=environment, capture_output=True, text=True
            )
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("HTTP(S) /v1 base URL", rejected.stderr)

    def test_preflight_materializes_direct_key_without_requiring_revision(self):
        environment = {
            **os.environ,
            "LOCAL_AGENT_MODEL_API_KEY": "third-party-or-local-key",
            "LOCAL_AGENT_MODEL_ENDPOINT": "http://models.example.test:8080/v1",
            "LOCAL_AGENT_MODEL_NAME": "code",
        }
        local_secret = ROOT / ".local" / "agent-model-api-key"
        local_secret.unlink(missing_ok=True)
        self.addCleanup(local_secret.unlink, missing_ok=True)
        accepted = subprocess.run(
            [str(PREFLIGHT)], cwd=ROOT, env=environment, capture_output=True, text=True
        )
        self.assertEqual(accepted.returncode, 0, accepted.stderr)
        self.assertEqual(local_secret.read_text(), "third-party-or-local-key\n")
        self.assertEqual(stat.S_IMODE(local_secret.stat().st_mode), 0o600)

    def test_preflight_rejects_invalid_model_output_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            key_file = pathlib.Path(directory) / "model-api-key"
            key_file.write_text("secret\n")
            key_file.chmod(stat.S_IRUSR | stat.S_IWUSR)
            environment = self.environment(key_file)
            environment["LOCAL_AGENT_MODEL_MAXIMUM_OUTPUT_TOKENS"] = "4097"
            rejected = subprocess.run(
                [str(PREFLIGHT)], cwd=ROOT, env=environment, capture_output=True, text=True
            )
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("integer from 64 to 4096", rejected.stderr)


if __name__ == "__main__":
    unittest.main()
