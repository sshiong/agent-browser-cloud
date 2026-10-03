import json
import pathlib
import subprocess
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "fixtures"))
from public_commerce_adapter import backpack_identity_expression


class PublicCommerceAdapterTest(unittest.TestCase):
    def test_entity_guards_precede_all_writes_and_annotation_is_idempotent(self):
        script = r"""
          const vm = require('node:vm');
          const assert = require('node:assert/strict');
          let input = ''; process.stdin.on('data', chunk => input += chunk);
          process.stdin.on('end', () => {
            const expression = JSON.parse(input);
            function fixture(variant) {
              const card = {}, other = {}, writes = [];
              const attributes = {href: '#'};
              const title = {tagName: 'A', textContent: 'Sauce Labs Backpack',
                closest: () => card, getAttribute: name => attributes[name] ?? null,
                setAttribute: (name, value) => { writes.push(name); attributes[name] = value; }};
              const image = {tagName: 'A', closest: () => card};
              const window = {}; window.top = window;
              const location = {origin: 'https://www.saucedemo.com', pathname: '/inventory.html'};
              let titles = [title], images = [image];
              if (variant === 'origin') location.origin = 'https://attacker.invalid';
              if (variant === 'route') location.pathname = '/cart.html';
              if (variant === 'frame') window.top = {};
              if (variant === 'duplicate') titles.push(title);
              if (variant === 'missing') images = [];
              if (variant === 'text') title.textContent = 'Sauce Labs Bike Light';
              if (variant === 'href') attributes.href = '/inventory-item.html?id=5';
              if (variant === 'card') image.closest = () => other;
              if (variant === 'tag') title.tagName = 'BUTTON';
              if (variant === 'conflict') attributes['data-agent-entity-hash'] = 'f'.repeat(64);
              const document = {querySelectorAll: selector =>
                selector === '#item_4_title_link' ? titles : images};
              return {context: {window, location, document}, writes, attributes};
            }
            for (const variant of ['origin','route','frame','duplicate','missing','text','href','card','tag','conflict']) {
              const {context, writes} = fixture(variant);
              assert.equal(vm.runInNewContext(expression, context).bound, false, variant);
              assert.equal(writes.length, 0, variant);
            }
            const valid = fixture('valid');
            for (let i = 0; i < 2; i++) {
              assert.equal(vm.runInNewContext(expression, valid.context).bound, true);
              assert.match(valid.attributes['data-agent-entity-hash'], /^[0-9a-f]{64}$/);
              assert.equal(valid.attributes['data-agent-entity-scope'], 'public-saucedemo');
              assert.equal(valid.attributes['data-agent-entity-type'], 'product-detail-title');
              assert.equal(valid.attributes.href, '#');
              assert.equal(valid.attributes['aria-label'], 'View details for Sauce Labs Backpack (product title)');
            }
            assert.equal(valid.writes.length, 8);
          });
        """
        key = "fixture-private-entity-key" + "x" * 32
        expression = backpack_identity_expression(key)
        self.assertNotIn(key, expression)
        result = subprocess.run(["node", "-e", script], input=json.dumps(expression),
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
