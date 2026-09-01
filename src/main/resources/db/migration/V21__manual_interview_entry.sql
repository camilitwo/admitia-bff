ALTER TABLE interviews
    ADD COLUMN entry_source VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN manual_reason TEXT,
    ADD COLUMN created_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE interviews
    ADD CONSTRAINT interviews_entry_source_check
        CHECK (entry_source IN ('STANDARD', 'MANUAL')),
    ADD CONSTRAINT interviews_manual_reason_check
        CHECK (entry_source <> 'MANUAL' OR length(btrim(manual_reason)) >= 5);

CREATE INDEX ix_interviews_entry_source
    ON interviews (entry_source)
    WHERE entry_source = 'MANUAL';
