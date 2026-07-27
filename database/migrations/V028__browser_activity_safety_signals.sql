-- Expand the durable Safe Point signal vocabulary for Browser Node CDP activity observations.
-- The old application remains compatible because this only permits additional values.
--
-- Add and validate the superset constraint before dropping the old one so there is no interval
-- without signal-type validation. PostgreSQL validates NOT VALID constraints without blocking
-- ordinary reads and writes for the duration of the table scan.

ALTER TABLE session_safety_signals
    ADD CONSTRAINT session_safety_signals_signal_type_check_v2
    CHECK (
        signal_type IN (
            'ACTIVE_INPUT',
            'ACTIVE_DRAG',
            'FILE_UPLOAD_ACTIVE',
            'FILE_DOWNLOAD_ACTIVE',
            'FORM_SUBMISSION_ACTIVE',
            'FILE_TRANSFER',
            'FORM_SUBMISSION',
            'PAYMENT_OR_SECURITY',
            'SNAPSHOT',
            'PROFILE_FLUSH',
            'CRITICAL_TRANSACTION',
            'BUSINESS_RECOVERY_UNKNOWN'
        )
    ) NOT VALID;

ALTER TABLE session_safety_signals
    VALIDATE CONSTRAINT session_safety_signals_signal_type_check_v2;

ALTER TABLE session_safety_signals
    DROP CONSTRAINT session_safety_signals_signal_type_check;

ALTER TABLE session_safety_signals
    RENAME CONSTRAINT session_safety_signals_signal_type_check_v2
    TO session_safety_signals_signal_type_check;
