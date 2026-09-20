#!/usr/bin/env python3
"""Generate/check bilingual README inventories from Git, excluding untracked backups/builds."""

import argparse
from pathlib import Path
import re
import subprocess
import sys

BEGIN = "<!-- BEGIN GENERATED MODULES -->"
END = "<!-- END GENERATED MODULES -->"
ROOTS = ("apps", "packages", "sdks", "database", "deploy", "tools")
README_FILES = (("README.md", "zh"), ("README.en.md", "en"))
INVENTORY_LABELS = {
    "zh": ("目录", "Git 跟踪的模块", "、"),
    "en": ("Directory", "Git-tracked modules", ", "),
}


def inventory(paths, language="zh"):
    if language not in INVENTORY_LABELS:
        raise ValueError(f"unsupported README language: {language}")
    root_label, module_label, separator = INVENTORY_LABELS[language]
    directories = {root: set() for root in ROOTS}
    for path in paths:
        parts = path.split("/")
        if len(parts) >= 3 and parts[0] in directories:
            directories[parts[0]].add(parts[1])
    rows = [BEGIN, "", f"| {root_label} | {module_label} |", "| --- | --- |"]
    for root, children in directories.items():
        links = separator.join(f"[{name}]({root}/{name}/)" for name in sorted(children))
        rows.append(f"| `{root}/` | {links} |")
    return "\n".join([*rows, "", END])


def replace_inventory(readme, generated):
    if readme.count(BEGIN) != 1 or readme.count(END) != 1:
        raise ValueError("README must have exactly one generated inventory block")
    start, end = readme.index(BEGIN), readme.index(END)
    if end < start:
        raise ValueError("README inventory markers are reversed")
    return readme[:start] + generated + readme[end + len(END):]


def broken_links(readme, root):
    # README uses simple repository-relative Markdown links. External/anchor links are excluded.
    links = re.findall(r"\[[^\]]*\]\(([^\s)]+)\)", readme)
    return sorted({link for link in links if ":" not in link and not link.startswith("#")
                   and not (root / link.split("#", 1)[0]).exists()})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    for filename, language in README_FILES:
        path = root / filename
        if not path.is_file():
            print(f"missing required README: {filename}", file=sys.stderr)
            return 1
        readme = path.read_text(encoding="utf-8")
        expected = replace_inventory(readme, inventory(paths, language))
        if args.write:
            path.write_text(expected, encoding="utf-8")
        elif readme != expected:
            print(
                f"{filename} inventory drift: stage module files, then run make docs-generate",
                file=sys.stderr,
            )
            return 1
        missing = broken_links(expected, root)
        if missing:
            print(f"{filename} broken local links: " + ", ".join(missing), file=sys.stderr)
            return 1
    print("bilingual README tracked-module inventories and local links: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
