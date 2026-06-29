-- Tabla para gestionar disponibilidad de vacantes por nivel/grado
CREATE TABLE grade_availability (
    id BIGSERIAL PRIMARY KEY,
    grade_level VARCHAR(50) NOT NULL UNIQUE,
    has_vacancy BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(255)
);

-- Índices para búsqueda rápida
CREATE INDEX idx_grade_availability_grade_level ON grade_availability(grade_level);
CREATE INDEX idx_grade_availability_has_vacancy ON grade_availability(has_vacancy);

-- Insertar los 14 niveles (PREKINDER a 4° MEDIO)
INSERT INTO grade_availability (grade_level, has_vacancy, updated_at) VALUES
    ('PREKINDER', TRUE, CURRENT_TIMESTAMP),
    ('KINDER', TRUE, CURRENT_TIMESTAMP),
    ('1_BASICO', TRUE, CURRENT_TIMESTAMP),
    ('2_BASICO', TRUE, CURRENT_TIMESTAMP),
    ('3_BASICO', TRUE, CURRENT_TIMESTAMP),
    ('4_BASICO', TRUE, CURRENT_TIMESTAMP),
    ('5_BASICO', TRUE, CURRENT_TIMESTAMP),
    ('6_BASICO', TRUE, CURRENT_TIMESTAMP),
    ('7_BASICO', TRUE, CURRENT_TIMESTAMP),
    ('8_BASICO', TRUE, CURRENT_TIMESTAMP),
    ('1_MEDIO', TRUE, CURRENT_TIMESTAMP),
    ('2_MEDIO', TRUE, CURRENT_TIMESTAMP),
    ('3_MEDIO', TRUE, CURRENT_TIMESTAMP),
    ('4_MEDIO', TRUE, CURRENT_TIMESTAMP);
