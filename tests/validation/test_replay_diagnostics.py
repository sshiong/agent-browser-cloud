import ast
import json
import pathlib
import unittest
from types import SimpleNamespace

from replay_diagnostics import diagnostic


MATRIX_PATH = pathlib.Path(__file__).resolve().parents[1] / "compatibility/real_url_agent_matrix.py"


class ReplayDiagnosticsTest(unittest.TestCase):
    def test_unclassified_secret_values_and_free_text_never_enter_errors(self):
        marker = "fixture-private-form-value"
        value = {
            "stateQuality": "COMPLETE", "targetRevision": 42,
            "url": f"https://example.invalid/callback?code={marker}", "title": marker,
            "lastError": marker, marker: marker,
            "targets": [{"role": "textbox", "name": marker, "value": marker,
                         "sensitive": False, "visible": False, "token": marker}],
            "executionResults": [{"toolId": "TYPE_TEXT", "status": "FAILED",
                                  "output": {"value": marker}}],
        }
        summary = diagnostic(value)
        self.assertNotIn(marker, json.dumps(summary))
        self.assertNotIn("https://", json.dumps(summary))
        self.assertEqual(summary["stateQuality"], "COMPLETE")
        self.assertEqual(summary["targetRevision"], 42)
        self.assertEqual(summary["targets"]["items"][0],
                         {"role": "textbox", "sensitive": False, "visible": False})
        self.assertEqual(diagnostic("123456"), {"redacted": True})
        self.assertEqual(diagnostic(123456), {"redacted": True})

    def test_large_nested_responses_are_bounded(self):
        value = {"items": [{"items": ["fixture-private"] * 100}] * 100}
        summary = diagnostic(value)
        self.assertEqual(summary["items"]["count"], 100)
        self.assertEqual(len(summary["items"]["items"]), 8)
        self.assertNotIn("fixture-private", json.dumps(summary))
        self.assertLess(len(json.dumps(summary)), 6000)

    def test_actual_task_failure_keeps_only_exact_known_navigation_codes(self):
        tree = ast.parse(MATRIX_PATH.read_text())
        functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)
                     and node.name in {"create_execute_task", "require_status"}]
        marker = "fixture-private-navigation-detail"
        for code in ["NAVIGATION_FAILED", "NAVIGATION_STATE_UNAVAILABLE"]:
            for value in [code, code + " " + marker]:
                failed = {"state": "FAILED", "lastError": value, "goal": marker,
                          "url": "https://example.invalid/?code=" + marker}
                responses = iter([(201, {"state": "PLANNED", "taskId": "fixture-task"}),
                                  (200, {"state": "RUNNING"})])
                scope = {"diagnostic": diagnostic, "request": lambda *args, **kwargs: next(responses),
                         "wait_for_executable_state": lambda *args: None,
                         "wait_for": lambda *args: failed,
                         "uuid": SimpleNamespace(uuid4=lambda: SimpleNamespace(hex="fixture"))}
                exec(compile(ast.Module(body=functions, type_ignores=[]), str(MATRIX_PATH), "exec"), scope)
                with self.assertRaises(AssertionError) as error:
                    scope["create_execute_task"]("fixture-session", {}, marker)
                self.assertNotIn(marker, str(error.exception))
                self.assertNotIn("https://", str(error.exception))
                self.assertEqual(code in str(error.exception), value == code)

    def test_actual_named_target_timeout_and_http_failure_do_not_dump_state(self):
        tree = ast.parse(MATRIX_PATH.read_text())
        functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)
                     and node.name in {"wait_for_named_target", "require_status"}]
        marker = "fixture-hidden-antiforgery-token"
        state = {"stateQuality": "COMPLETE", "pageActivity": "ACTIVE",
                 "url": "https://example.invalid/?code=" + marker, "title": marker,
                 "targets": [{"role": "textbox", "name": marker, "value": marker,
                              "sensitive": False, "visible": False, "enabled": True}]}
        ticks = iter([0, 0, 2])
        scope = {"diagnostic": diagnostic, "request": lambda *args: (200, state),
                 "time": SimpleNamespace(monotonic=lambda: next(ticks), sleep=lambda _: None)}
        exec(compile(ast.Module(body=functions, type_ignores=[]), str(MATRIX_PATH), "exec"), scope)
        with self.assertRaises(AssertionError) as timeout:
            scope["wait_for_named_target"]("fixture-session", "Login", timeout=1, require_stable=True)
        with self.assertRaises(AssertionError) as failed:
            scope["require_status"]((503, state), 200, "fixture request")
        for error in (timeout.exception, failed.exception):
            self.assertNotIn(marker, str(error))
            self.assertNotIn("https://", str(error))
            self.assertIn("COMPLETE", str(error))
        self.assertIn("503", str(failed.exception))

    def test_every_assertion_interpolation_passes_through_diagnostic(self):
        tree = ast.parse(MATRIX_PATH.read_text())
        for node in ast.walk(tree):
            if not (isinstance(node, ast.Raise) and isinstance(node.exc, ast.Call)
                    and getattr(node.exc.func, "id", None) == "AssertionError"):
                continue
            for item in ast.walk(node.exc):
                if isinstance(item, ast.FormattedValue):
                    self.assertIsInstance(item.value, ast.Call, f"unsafe assertion line {node.lineno}")
                    self.assertEqual(getattr(item.value.func, "id", None), "diagnostic",
                                     f"unsafe assertion line {node.lineno}")


if __name__ == "__main__":
    unittest.main()
