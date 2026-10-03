"""Tenant-owned identity binding for the published SauceDemo Backpack test item.

This fixture supplies identity data through the governed evaluation API. It does not
click, navigate, change interaction flags or attest a successful business outcome.
"""

import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "apps/application-adapter"))
from application_adapter import entity_identity_attributes


DETAIL_TARGET_NAME = "View details for Sauce Labs Backpack (product title)"


def backpack_identity_expression(identity_key):
    attributes = entity_identity_attributes(
        "4", "public-saucedemo", "product-detail-title", identity_key
    )
    # All guards run before the first write. IDs represent the site's published product
    # entity; neither DOM order nor position is used to choose between identical targets.
    return """(() => {
      if (window !== window.top || location.origin !== 'https://www.saucedemo.com'
          || location.pathname !== '/inventory.html') return {bound: false};
      const titles = document.querySelectorAll('#item_4_title_link');
      const images = document.querySelectorAll('#item_4_img_link');
      if (titles.length !== 1 || images.length !== 1) return {bound: false};
      const title = titles[0], image = images[0];
      const card = title.closest('.inventory_item');
      if (title.tagName !== 'A' || image.tagName !== 'A'
          || title.textContent.trim() !== 'Sauce Labs Backpack'
          || !card || image.closest('.inventory_item') !== card) return {bound: false};
      const href = title.getAttribute('href');
      if (href !== '#' && href !== '/inventory-item.html?id=4'
          && href !== 'inventory-item.html?id=4') return {bound: false};
      const attributes = ATTRIBUTES;
      for (const [name, value] of Object.entries(attributes)) {
        const existing = title.getAttribute(name);
        if (existing !== null && existing !== value) return {bound: false};
      }
      for (const [name, value] of Object.entries(attributes)) title.setAttribute(name, value);
      title.setAttribute('aria-label', LABEL);
      return {bound: true};
    })()""".replace("ATTRIBUTES", json.dumps(attributes)).replace(
        "LABEL", json.dumps(DETAIL_TARGET_NAME)
    )
