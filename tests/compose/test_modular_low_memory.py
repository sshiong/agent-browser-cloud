import json
import os
import pathlib
import subprocess
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
STACK = ROOT / "tools/local-dev/stack.sh"


class ModularLowMemoryComposeTest(unittest.TestCase):
    def render(self, *args: str) -> dict:
        env = {
            **os.environ,
            "COMPOSE_PROFILES": "",
            "LOCAL_AGENT_MODEL_API_KEY_FILE": str(ROOT / ".local/owned-test-placeholder"),
            "LOCAL_AGENT_MODEL_ENDPOINT": "https://models.example.test/v1",
            "LOCAL_AGENT_MODEL_NAME": "owned-test",
        }
        output = subprocess.check_output(
            [str(STACK), *args, "config", "--", "--format", "json"],
            cwd=ROOT,
            text=True,
            env=env,
            stderr=subprocess.DEVNULL,
        )
        return json.loads(output)

    def test_minimal_mcp_preset_drops_web_workers_vnc_recording_and_applies_low_memory_caps(self):
        cfg = self.render("--preset", "minimal-mcp")
        services = cfg["services"]

        self.assertIn("mcp-gateway", services)
        self.assertNotIn("web-console", services)
        for worker in (
            "local-agent-secrets",
            "agent-worker",
            "reviewer-worker",
            "outcome-verifier-worker",
            "vision-worker",
        ):
            self.assertNotIn(worker, services)

        cp = services["control-plane"]
        self.assertEqual(int(cp["mem_limit"]), 448 * 1024 * 1024)
        self.assertEqual(cp["environment"]["AGENT_EXTERNAL_WORKER_ENABLED"], "false")
        self.assertEqual(cp["environment"]["AGENT_REVIEWER_EXTERNAL_ENABLED"], "false")
        self.assertEqual(cp["environment"]["AGENT_OUTCOME_VERIFIER_EXTERNAL_ENABLED"], "false")
        self.assertIn("-Xmx288m", cp["environment"]["JAVA_OPTS"])
        self.assertIn("-XX:+UseSerialGC", cp["environment"]["JAVA_OPTS"])
        self.assertEqual(cp["environment"]["DATABASE_POOL_MAX_SIZE"], "5")
        self.assertEqual(cp["environment"]["SERVER_TOMCAT_THREADS_MAX"], "24")

        node = services["browser-node"]
        self.assertEqual(node["build"]["args"]["ENABLE_REMOTE_DESKTOP"], "false")
        self.assertEqual(node["build"]["args"]["ENABLE_RECORDING_SCANNER"], "false")
        self.assertEqual(node["environment"]["BROWSER_LOW_MEMORY_MODE"], "true")
        self.assertEqual(node["environment"]["XVFB_PATH"], "")
        self.assertEqual(node["environment"]["X11VNC_PATH"], "")
        self.assertEqual(node["environment"]["NODE_MAX_SESSIONS"], "2")
        self.assertEqual(int(node["mem_limit"]), 1536 * 1024 * 1024)

        self.assertEqual(int(services["postgres"]["mem_limit"]), 192 * 1024 * 1024)
        self.assertIn("shared_buffers=32MB", services["postgres"]["command"])
        self.assertEqual(int(services["redis"]["mem_limit"]), 64 * 1024 * 1024)
        self.assertIn("48mb", services["redis"]["command"])

    def test_can_toggle_mcp_web_vnc_recording_and_workers_independently(self):
        cfg = self.render(
            "--no-mcp",
            "--web",
            "--no-vnc",
            "--recording",
            "--no-workers",
            "--low-memory",
            "--max-sessions",
            "1",
            "--node-mem-mb",
            "1024",
        )
        services = cfg["services"]
        self.assertNotIn("mcp-gateway", services)
        self.assertIn("web-console", services)
        self.assertNotIn("agent-worker", services)

        node = services["browser-node"]
        self.assertEqual(node["build"]["args"]["ENABLE_REMOTE_DESKTOP"], "false")
        self.assertEqual(node["build"]["args"]["ENABLE_RECORDING_SCANNER"], "true")
        self.assertEqual(node["environment"]["NODE_MAX_SESSIONS"], "1")
        self.assertEqual(node["environment"]["NODE_CERTIFIED_MEMORY_MIB"], "1024")
        self.assertEqual(int(node["mem_limit"]), 1024 * 1024 * 1024)

    def test_full_preset_enables_all_modules_and_external_workers_without_low_memory_caps(self):
        cfg = self.render("--preset", "full")
        services = cfg["services"]
        for expected in (
            "mcp-gateway",
            "web-console",
            "local-agent-secrets",
            "agent-worker",
            "reviewer-worker",
            "outcome-verifier-worker",
            "vision-worker",
        ):
            self.assertIn(expected, services)

        cp = services["control-plane"]
        self.assertNotIn("mem_limit", cp)
        self.assertEqual(cp["environment"]["AGENT_EXTERNAL_WORKER_ENABLED"], "true")
        self.assertEqual(cp["environment"]["AGENT_REVIEWER_EXTERNAL_ENABLED"], "true")
        self.assertEqual(cp["environment"]["AGENT_OUTCOME_VERIFIER_EXTERNAL_ENABLED"], "true")

        node = services["browser-node"]
        self.assertEqual(node["build"]["args"]["ENABLE_REMOTE_DESKTOP"], "true")
        self.assertEqual(node["build"]["args"]["ENABLE_RECORDING_SCANNER"], "true")
        self.assertEqual(node["environment"]["BROWSER_LOW_MEMORY_MODE"], "false")
        self.assertEqual(node["environment"]["XVFB_PATH"], "/usr/bin/Xvfb")
        self.assertEqual(node["environment"]["X11VNC_PATH"], "/usr/bin/x11vnc")


if __name__ == "__main__":
    unittest.main()
