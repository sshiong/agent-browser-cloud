-- Profile 检查点、写入世代与恢复状态。
ALTER TABLE profiles
    ADD COLUMN profile_write_epoch BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN core_size_bytes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN checkpoint_file_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN restore_status TEXT NOT NULL DEFAULT 'EMPTY',
    ADD COLUMN last_checkpoint_at TIMESTAMPTZ;

ALTER TABLE profiles
    ADD CONSTRAINT chk_profiles_non_negative_epochs
        CHECK (
            profile_write_epoch >= 0
            AND COALESCE(latest_checkpoint_epoch, 0) >= 0
            AND core_size_bytes >= 0
            AND checkpoint_file_count >= 0
        ),
    ADD CONSTRAINT chk_profiles_restore_status
        CHECK (restore_status IN ('EMPTY', 'TECHNICAL_READY'));
