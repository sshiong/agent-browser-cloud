import pathlib
import subprocess
import unittest
import json
import os

ROOT = pathlib.Path(__file__).resolve().parents[2]

class OptionalMcpDeployment(unittest.TestCase):
    def test_gateway_is_excluded_by_default_and_isolated_when_enabled(self):
        # Expand the real Compose configuration with non-secret test inputs; no services start.
        compose = json.loads(subprocess.check_output(['docker','compose','-f',str(ROOT/'docker-compose.yml'),'--profile','mcp','config','--format','json'],text=True, env={**os.environ, 'LOCAL_AGENT_MODEL_API_KEY_FILE':str(ROOT/'.local/owned-test-placeholder'), 'LOCAL_AGENT_MODEL_ENDPOINT':'https://models.example.test/v1','LOCAL_AGENT_MODEL_NAME':'owned-test'}))
        environment={**os.environ,'COMPOSE_PROFILES':'','LOCAL_AGENT_MODEL_API_KEY_FILE':str(ROOT/'.local/owned-test-placeholder'),'LOCAL_AGENT_MODEL_ENDPOINT':'https://models.example.test/v1','LOCAL_AGENT_MODEL_NAME':'owned-test'}
        default_services=subprocess.check_output(['docker','compose','-f',str(ROOT/'docker-compose.yml'),'config','--services'],text=True,env=environment).splitlines()
        self.assertNotIn('mcp-gateway',default_services)
        service=compose['services']['mcp-gateway']
        self.assertEqual(service['profiles'],['mcp'])
        self.assertEqual(service['ports'][0]['host_ip'],'127.0.0.1')
        self.assertEqual(service['ports'][0]['target'],8130)
        self.assertTrue(service['read_only'])
        self.assertNotIn('volumes',service)
        self.assertNotIn('mcp-gateway',compose['services']['control-plane'].get('depends_on',{}))
        base=(ROOT/'deploy/kubernetes/base/kustomization.yaml').read_text()
        self.assertNotIn('optional/mcp',base)
