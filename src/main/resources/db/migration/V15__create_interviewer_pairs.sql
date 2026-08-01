CREATE TABLE interviewer_pairs (
    id BIGSERIAL PRIMARY KEY,
    cycle_director_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    psychologist_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    revision INTEGER NOT NULL DEFAULT 1,
    supersedes_pair_id BIGINT REFERENCES interviewer_pairs(id) ON DELETE SET NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    archived_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT interviewer_pairs_distinct_members CHECK (cycle_director_id <> psychologist_id),
    CONSTRAINT interviewer_pairs_revision_positive CHECK (revision > 0)
);

CREATE TABLE interviewer_pair_grades (
    pair_id BIGINT NOT NULL REFERENCES interviewer_pairs(id) ON DELETE CASCADE,
    grade_code VARCHAR(30) NOT NULL,
    PRIMARY KEY (pair_id, grade_code)
);

CREATE UNIQUE INDEX ux_interviewer_pairs_active_director
    ON interviewer_pairs (cycle_director_id)
    WHERE active = TRUE;

CREATE UNIQUE INDEX ux_interviewer_pairs_active_psychologist
    ON interviewer_pairs (psychologist_id)
    WHERE active = TRUE;

CREATE INDEX ix_interviewer_pair_grades_grade
    ON interviewer_pair_grades (grade_code, pair_id);

ALTER TABLE interviews
    ADD COLUMN interviewer_pair_id BIGINT REFERENCES interviewer_pairs(id) ON DELETE SET NULL;

CREATE INDEX ix_interviews_interviewer_pair_id
    ON interviews (interviewer_pair_id);

COMMENT ON COLUMN interviews.interviewer_pair_id IS
    'Pareja versionada usada al crear la entrevista. Nullable para preservar entrevistas históricas.';
