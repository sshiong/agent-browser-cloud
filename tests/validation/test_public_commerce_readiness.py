import ast
import copy
import pathlib
import unittest
from types import SimpleNamespace

from replay_diagnostics import diagnostic


MATRIX = pathlib.Path(__file__).resolve().parents[1] / "compatibility/real_url_agent_matrix.py"


class PublicCommerceReadinessTest(unittest.TestCase):
    def scope(self):
        tree = ast.parse(MATRIX.read_text())
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef)
                        and node.name == "public_commerce_detail_target")
        scope = {"DETAIL_TARGET_NAME": "View details for Sauce Labs Backpack (product title)"}
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(MATRIX), "exec"), scope)
        return scope["public_commerce_detail_target"]

    def state(self):
        button = {"role": "button", "interactive": True, "visible": True, "enabled": True,
                  "inViewport": True, "occluded": False}
        return {"url": "https://www.saucedemo.com/inventory-item.html?id=4",
                "stateQuality": "COMPLETE", "freshness": "FRESH", "pageActivity": "STABLE",
                "pageStability": {"evidenceFresh": True, **{component + "QuietMillis": 2000
                    for component in ("dom", "layout", "focus", "route")}},
                "targets": [{**button, "name": "Add to cart", "targetRef": "detail-add"},
                            {**button, "name": "Back to products"}]}

    def test_router_url_with_old_inventory_targets_is_not_detail_readiness(self):
        state = self.state()
        add = state["targets"][0]
        state["targets"] = [copy.deepcopy(add) for _ in range(6)]
        state["targets"].append({"name": "View details for Sauce Labs Backpack (product title)"})
        self.assertIsNone(self.scope()(state, state["url"]))

    def test_previous_named_target_wait_accepts_the_old_inventory_button(self):
        state = self.state()
        state["targets"] = [{**state["targets"][0], "targetRef": "old-inventory-add"} for _ in range(6)]
        function = next(node for node in ast.parse(MATRIX.read_text()).body
                        if isinstance(node, ast.FunctionDef) and node.name == "wait_for_named_target")
        scope = {"request": lambda *args: (200, state), "diagnostic": diagnostic,
                 "time": SimpleNamespace(monotonic=lambda: 0, sleep=lambda _: None)}
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(MATRIX), "exec"), scope)
        _, selected = scope["wait_for_named_target"]("owned-session", "Add to cart")
        self.assertEqual(selected["targetRef"], "old-inventory-add")
        self.assertIsNone(self.scope()(state, state["url"]))

    def test_requires_exact_route_stability_unique_semantics_and_actionable_controls(self):
        ready = self.scope()
        state = self.state()
        self.assertIs(ready(state, state["url"]), state["targets"][0])
        variants = []
        for key, value in [("freshness", "STALE"), ("stateQuality", "DEPTH_LIMITED"),
                           ("pageActivity", "CHANGING"), ("url", "https://www.saucedemo.com/cart.html")]:
            changed = copy.deepcopy(state); changed[key] = value; variants.append(changed)
        for key, value in [("evidenceFresh", False), ("routeQuietMillis", 1999),
                           ("focusQuietMillis", 0), ("domQuietMillis", 0), ("layoutQuietMillis", 0)]:
            changed = copy.deepcopy(state); changed["pageStability"][key] = value; variants.append(changed)
        for index in (0, 1):
            for key, value in [("interactive", False), ("visible", False), ("enabled", False),
                               ("inViewport", False), ("occluded", True), ("role", "link")]:
                changed = copy.deepcopy(state); changed["targets"][index][key] = value; variants.append(changed)
        missing = copy.deepcopy(state); missing["targets"].pop(); variants.append(missing)
        duplicate = copy.deepcopy(state); duplicate["targets"].append(duplicate["targets"][0]); variants.append(duplicate)
        inventory = copy.deepcopy(state); inventory["targets"].append({"name": "View details for Sauce Labs Backpack (product title)"}); variants.append(inventory)
        for changed in variants:
            with self.subTest(state=changed):
                self.assertIsNone(ready(changed, state["url"]))


if __name__ == "__main__":
    unittest.main()
