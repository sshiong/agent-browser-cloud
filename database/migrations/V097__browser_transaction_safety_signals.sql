-- Expand Browser Node Safe Point vocabulary for privacy-bounded CDP transaction observations.
-- Old Control Plane binaries ignore the new protobuf fields and never write these values. Old
-- Browser Nodes do not advertise the matching capability, so mixed-version placement remains safe.
--
-- Keep validation continuously enforced: add the superset as NOT VALID, validate existing rows,
-- then replace the old constraint only after the scan succeeds.

ALTER TABLE session_safety_signals
    ADD CONSTRAINT session_safety_signals_signal_type_check_v3
    CHECK (
        signal_type IN (
            'ACTIVE_INPUT',
            'ACTIVE_DRAG',
            'FILE_UPLOAD_ACTIVE',
            'FILE_DOWNLOAD_ACTIVE',
            'FORM_SUBMISSION_ACTIVE',
            'SPA_MUTATION_ACTIVE',
            'PAYMENT_OR_SECURITY_ACTIVE',
            'CRITICAL_TRANSACTION_ACTIVE',
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
    VALIDATE CONSTRAINT session_safety_signals_signal_type_check_v3;

ALTER TABLE session_safety_signals
    DROP CONSTRAINT session_safety_signals_signal_type_check;

ALTER TABLE session_safety_signals
    RENAME CONSTRAINT session_safety_signals_signal_type_check_v3
    TO session_safety_signals_signal_type_check;
