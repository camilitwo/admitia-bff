ALTER TABLE inclusion_record_revisions
    ADD COLUMN change_origin VARCHAR(32) NOT NULL DEFAULT 'FAMILY'
        CHECK (change_origin IN ('FAMILY', 'ADMIN_DIRECT_EDIT')),
    ADD COLUMN reason_ciphertext TEXT,
    ADD COLUMN reason_iv VARCHAR(64),
    ADD COLUMN reason_wrapped_dek TEXT,
    ADD COLUMN reason_wrapped_dek_iv VARCHAR(64),
    ADD COLUMN reason_key_version VARCHAR(32);

ALTER TABLE inclusion_record_revisions
    ADD CONSTRAINT chk_inclusion_admin_reason_complete CHECK (
        change_origin <> 'ADMIN_DIRECT_EDIT'
        OR (reason_ciphertext IS NOT NULL
            AND reason_iv IS NOT NULL
            AND reason_wrapped_dek IS NOT NULL
            AND reason_wrapped_dek_iv IS NOT NULL
            AND reason_key_version IS NOT NULL)
    );

COMMENT ON COLUMN inclusion_record_revisions.change_origin IS
    'Origen de una revisión individual: envío familiar o corrección administrativa directa.';

CREATE INDEX idx_inclusion_revisions_timeline
    ON inclusion_record_revisions(inclusion_id, revision_number DESC);
