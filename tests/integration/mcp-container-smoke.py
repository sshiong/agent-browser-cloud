"""Build the shipped MCP image and run the official-client protocol suite against it.

The upstream API is an explicit fixture; formal Java/PG lifecycle coverage is separate.
Only this invocation's randomly named image/container is removed.
macOS callers must first verify the repository's OrbStack prerequisites.
"""
import os
from pathlib import Path
import subprocess
import sys
import uuid


def main():
    root = Path(__file__).resolve().parents[2]
    image = 'browsercloud-mcp-smoke:' + uuid.uuid4().hex
    try:
        subprocess.run(['docker', 'build', '-t', image, 'apps/mcp-gateway'], cwd=root, check=True)
        subprocess.run([sys.executable, '-m', 'unittest', 'discover',
            '-s', 'apps/mcp-gateway', '-p', 'test_gateway.py'], cwd=root,
            env={**os.environ, 'MCP_TEST_CONTAINER_IMAGE': image}, check=True)
    finally:
        subprocess.run(['docker', 'image', 'rm', image], cwd=root,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)


if __name__ == '__main__':
    main()
