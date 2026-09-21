ALTER TABLE applications
    ADD COLUMN client_submission_id UUID;

CREATE UNIQUE INDEX uq_prekinder_application_client_submission
    ON applications(submitted_by, process_id, client_submission_id)
    WHERE client_submission_id IS NOT NULL;

COMMENT ON COLUMN applications.client_submission_id IS
    'Identificador estable generado por el cliente para reintentar el alta sin duplicar la postulación.';
