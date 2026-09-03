from pathlib import Path
import tempfile
import unittest

from check_readme import BEGIN, END, broken_links, inventory, replace_inventory


class ReadmeInventoryTest(unittest.TestCase):
    def test_inventory_is_sorted_deduplicated_and_excludes_unrelated_roots(self):
        result = inventory(["apps/z/main.rs", "apps/a/main.py", "apps/z/Cargo.toml",
                            "backup/apps/old/main.py", "README.md"])
        self.assertIn("[a](apps/a/)、[z](apps/z/)", result)
        self.assertEqual(result.count("apps/z/"), 1)
        self.assertNotIn("old", result)

    def test_addition_and_deletion_change_inventory(self):
        self.assertNotEqual(inventory([]), inventory(["apps/new/main.py"]))

    def test_generation_preserves_surrounding_prose_and_is_idempotent(self):
        readme = "before\n" + BEGIN + "\nstale\n" + END + "\nafter\n"
        generated = inventory(["apps/new/main.py"])
        updated = replace_inventory(readme, generated)
        self.assertEqual(updated, "before\n" + generated + "\nafter\n")
        self.assertEqual(replace_inventory(updated, generated), updated)

    def test_missing_duplicate_or_reversed_markers_fail(self):
        for readme in ("", BEGIN + END + BEGIN, END + BEGIN):
            with self.assertRaises(ValueError):
                replace_inventory(readme, inventory([]))

    def test_local_link_checks_ignore_external_and_anchor_links(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "module").mkdir()
            readme = "[ok](module/) [missing](removed/) [web](https://example.com) [a](#test)"
            self.assertEqual(broken_links(readme, root), ["removed/"])


if __name__ == "__main__":
    unittest.main()
