ALTER TABLE browser_placements
  ADD COLUMN extension_cpu_weight INTEGER NOT NULL DEFAULT 100;

ALTER TABLE browser_placements
  ADD CONSTRAINT chk_browser_placements_extension_cpu_weight
    CHECK (extension_cpu_weight BETWEEN 1 AND 10000);
