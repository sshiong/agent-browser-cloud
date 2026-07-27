-- V027__session_non_cgroup_resource_actuators.sql
-- 将 State Collector 与 Remote Desktop 的在线资源边界纳入 Placement 权威状态。

ALTER TABLE browser_placements
    ADD COLUMN state_collector_budget_percent INTEGER NOT NULL DEFAULT 50,
    ADD COLUMN remote_desktop_bitrate_kbps INTEGER NOT NULL DEFAULT 0;

UPDATE browser_placements
SET remote_desktop_bitrate_kbps = 8000
WHERE requires_desktop
  AND remote_desktop_bitrate_kbps = 0;

ALTER TABLE browser_placements
    ADD CONSTRAINT chk_browser_placement_state_collector_budget CHECK (
        state_collector_budget_percent BETWEEN 10 AND 100
    ),
    ADD CONSTRAINT chk_browser_placement_remote_desktop_bitrate CHECK (
        remote_desktop_bitrate_kbps BETWEEN 0 AND 100000
        AND (
            (NOT requires_desktop AND remote_desktop_bitrate_kbps = 0)
            OR (requires_desktop AND remote_desktop_bitrate_kbps BETWEEN 250 AND 100000)
        )
    );

COMMENT ON COLUMN browser_placements.state_collector_budget_percent IS
'Live State Collector CPU/work budget acknowledged by Browser Node';
COMMENT ON COLUMN browser_placements.remote_desktop_bitrate_kbps IS
'Live per-Session VNC server-to-client bitrate boundary acknowledged by Browser Node';
