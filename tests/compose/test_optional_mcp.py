import pathlib
import subprocess
import unittest
import json

ROOT = pathlib.Path(__file__).resolve().parents[2]

class OptionalMcpDeployment(unittest.TestCase):
    def test_gateway_is_excluded_by_default_and_isolated_when_enabled(self):
        # Parse the actual YAML without requiring Docker runtime or model credentials.
        compose = json.loads(subprocess.check_output(['docker','compose','-f',str(ROOT/'docker-compose.yml'),'config','--no-interpolate','--format','json'],text=True))
        service=compose['services']['mcp-gateway']
        self.assertEqual(service['profiles'],['mcp'])
        self.assertEqual(service['ports'][0]['host_ip'],'127.0.0.1')
        self.assertEqual(service['ports'][0]['target'],8130)
        self.assertTrue(service['read_only'])
        self.assertNotIn('volumes',service)
        self.assertNotIn('mcp-gateway',compose['services']['control-plane'].get('depends_on',{}))
        base=(ROOT/'deploy/kubernetes/base/kustomization.yaml').read_text()
        self.assertNotIn('optional/mcp',base)
