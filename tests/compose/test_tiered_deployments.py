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

    def test_l1_personal_defaults_to_core_headless_node_and_384m_jvm_without_workers(self):
        cfg = self.compose_json(
            [
                "deploy/compose/modular.yml",
                "deploy/compose/low-memory.yml",
                "deploy/compose/personal.yml",
            ],
            profiles=["web", "mcp"],
        )
        services = cfg["services"]
        self.assertIn("control-plane", services)
        self.assertIn("postgres", services)
        self.assertIn("web-console", services)
        self.assertIn("mcp-gateway", services)
        self.assertNotIn("agent-worker", services)
        self.assertEqual(services["browser-node"]["build"]["target"], "core")
        self.assertIn("-Xmx384m", services["control-plane"]["environment"]["JAVA_OPTS"])
        self.assertEqual(int(services["control-plane"]["mem_limit"]), 512 * 1024 * 1024)

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
