import json
import os
import pathlib
import subprocess
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class TieredDeploymentComposeTest(unittest.TestCase):
    def compose_json(self, files: list[str], profiles: list[str] | None = None, extra_env: dict[str, str] | None = None) -> dict:
        cmd = ["docker", "compose"]
        for file in files:
            cmd.extend(["-f", str(ROOT / file)])
        for profile in profiles or []:
            cmd.extend(["--profile", profile])
        cmd.extend(["config", "--format", "json"])
        env = {
            **os.environ,
            "COMPOSE_PROFILES": "",
            "LOCAL_AGENT_MODEL_API_KEY_FILE": str(ROOT / ".local/owned-test-placeholder"),
            "LOCAL_AGENT_MODEL_ENDPOINT": "https://models.example.test/v1",
            "LOCAL_AGENT_MODEL_NAME": "owned-test",
            **(extra_env or {}),
        }
        out = subprocess.check_output(cmd, cwd=ROOT, text=True, env=env)
        return json.loads(out)

    def test_l0_lite_is_single_pure_rust_container_without_java_pg_redis_or_python(self):
        cfg = self.compose_json(["deploy/compose/lite.yml"])
        services = cfg["services"]
        self.assertEqual(list(services.keys()), ["browsercloud-lite"])
        lite = services["browsercloud-lite"]
        self.assertEqual(lite["build"]["target"], "lite")
        self.assertEqual(lite["ports"][0]["host_ip"], "127.0.0.1")
        self.assertEqual(lite["ports"][0]["target"], 8130)
        self.assertEqual(lite["environment"]["BROWSER_LOW_MEMORY_MODE"], "true")
        self.assertEqual(lite["environment"]["LITE_MAX_SESSIONS"], "4")
        self.assertEqual(int(lite["mem_limit"]), 1536 * 1024 * 1024)
        hc_test = lite["healthcheck"]["test"]
        self.assertEqual(hc_test, ["CMD", "/usr/local/bin/browsercloud-lite", "--healthcheck"])
        self.assertNotIn("/dev/tcp", " ".join(hc_test))

    def test_l1_personal_defaults_to_core_headless_node_and_384m_jvm_without_workers(self):
        cfg = self.compose_json(
            [
                "deploy/compose/modular.yml",
                "deploy/compose/low-memory.yml",
                "deploy/compose/personal.yml",
                "deploy/compose/mcp-gateway-rust.yml",
            ],
            profiles=["web", "mcp"],
        )
        services = cfg["services"]
        self.assertIn("control-plane", services)
        self.assertIn("postgres", services)
        self.assertIn("web-console", services)
        self.assertIn("mcp-gateway", services)
        self.assertNotIn("agent-worker", services)
        node = services["browser-node"]
        self.assertEqual(node["build"]["target"], "core")
        self.assertIn("-Xmx384m", services["control-plane"]["environment"]["JAVA_OPTS"])
        self.assertEqual(int(services["control-plane"]["mem_limit"]), 512 * 1024 * 1024)
        # P0: Compose defaults MUST stay strictly within Rust node-agent validation bounds.
        diff_max_bytes = int(node["environment"]["STATE_DIFF_MAX_BYTES"])
        self.assertGreaterEqual(diff_max_bytes, 1024)
        self.assertLessEqual(diff_max_bytes, 60000)
        res_interval = int(node["environment"]["SESSION_RESOURCE_REPORT_INTERVAL_SECONDS"])
        self.assertGreaterEqual(res_interval, 5)
        self.assertLessEqual(res_interval, 3600)
        proxy_interval = int(node["environment"]["PROXY_HEALTH_PROBE_INTERVAL_SECONDS"])
        self.assertGreaterEqual(proxy_interval, 15)
        self.assertLessEqual(proxy_interval, 3600)
        # Headless core target must not pass non-existent Xvfb/x11vnc paths by default.
        self.assertEqual(node["environment"]["XVFB_PATH"], "")
        self.assertEqual(node["environment"]["X11VNC_PATH"], "")
        # P1: Rust MCP overlay must override Python healthcheck with native Rust probe.
        mcp_hc = services["mcp-gateway"]["healthcheck"]["test"]
        self.assertEqual(mcp_hc, ["CMD", "/usr/local/bin/browsercloud-mcp-gateway", "--healthcheck"])
        self.assertNotIn("python", " ".join(mcp_hc))

    def test_l2_team_uses_core_node_by_default_and_allows_desktop_or_vision_override(self):
        cfg = self.compose_json(
            ["deploy/compose/modular.yml", "deploy/compose/team.yml"],
            profiles=["web", "mcp"],
            extra_env={"BROWSER_NODE_TARGET": "desktop"},
        )
        services = cfg["services"]
        self.assertEqual(services["browser-node"]["build"]["target"], "desktop")
        self.assertEqual(services["browser-node"]["environment"]["NODE_MAX_SESSIONS"], "6")
        self.assertIn("-Xmx768m", services["control-plane"]["environment"]["JAVA_OPTS"])
        self.assertNotIn("agent-worker", services)


if __name__ == "__main__":
    unittest.main()
