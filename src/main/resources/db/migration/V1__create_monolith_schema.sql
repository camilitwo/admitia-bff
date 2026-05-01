CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(40) NOT NULL,
    subject VARCHAR(50),
    educational_level VARCHAR(50),
    rut VARCHAR(20),
    phone VARCHAR(30),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    preferences_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS email_verification_codes (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    code VARCHAR(10) NOT NULL,
    rut VARCHAR(20),
    used BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS active_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_activity TIMESTAMP NOT NULL DEFAULT NOW(),
    user_agent TEXT,
    ip_address VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS parents (
    id BIGSERIAL PRIMARY KEY,
    full_name VARCHAR(200) NOT NULL,
    rut VARCHAR(20),
    email VARCHAR(255),
    phone VARCHAR(30),
    address TEXT,
    profession VARCHAR(255),
    workplace VARCHAR(255),
    parent_type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS guardians (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    full_name VARCHAR(200) NOT NULL,
    rut VARCHAR(20),
    email VARCHAR(255),
    phone VARCHAR(30),
    relationship VARCHAR(100),
    address TEXT,
    profession VARCHAR(255),
    workplace VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS supporters (
    id BIGSERIAL PRIMARY KEY,
    full_name VARCHAR(200) NOT NULL,
    rut VARCHAR(20),
    email VARCHAR(255),
    phone VARCHAR(30),
    relationship VARCHAR(100),
    address TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS students (
    id BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    paternal_last_name VARCHAR(100),
    maternal_last_name VARCHAR(100),
    rut VARCHAR(20),
    birth_date DATE,
    email VARCHAR(255),
    address TEXT,
    grade_applied VARCHAR(50),
    target_school VARCHAR(50),
    current_school VARCHAR(255),
    special_needs BOOLEAN NOT NULL DEFAULT FALSE,
    special_needs_description TEXT,
    additional_notes TEXT,
    age INTEGER,
    pais VARCHAR(100) DEFAULT 'Chile',
    region VARCHAR(100),
    comuna VARCHAR(100),
    admission_preference VARCHAR(50),
    is_employee_child BOOLEAN NOT NULL DEFAULT FALSE,
    employee_parent_name VARCHAR(255),
    is_alumni_child BOOLEAN NOT NULL DEFAULT FALSE,
    alumni_parent_year INTEGER,
    is_inclusion_student BOOLEAN NOT NULL DEFAULT FALSE,
    inclusion_type VARCHAR(100),
    inclusion_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS applications (
    id BIGSERIAL PRIMARY KEY,
    student_id BIGINT NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    father_id BIGINT REFERENCES parents(id) ON DELETE SET NULL,
    mother_id BIGINT REFERENCES parents(id) ON DELETE SET NULL,
    supporter_id BIGINT REFERENCES supporters(id) ON DELETE SET NULL,
    guardian_id BIGINT REFERENCES guardians(id) ON DELETE SET NULL,
    applicant_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    submission_date TIMESTAMP NOT NULL DEFAULT NOW(),
    notes TEXT,
    documentos_completos BOOLEAN NOT NULL DEFAULT FALSE,
    last_document_notification_at TIMESTAMP,
    deleted_at TIMESTAMP,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS complementary_forms (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL UNIQUE REFERENCES applications(id) ON DELETE CASCADE,
    form_data JSONB NOT NULL,
    is_submitted BOOLEAN NOT NULL DEFAULT FALSE,
    submitted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
--Caused by: jakarta.persistence.PersistenceException: [PersistenceUnit: default] Unable to build Hibernate SessionFactory; nested exception is org.hibernate.tool.schema.spi.SchemaManagementException: Schema-validation: missing column [created_at] in table [documents]
CREATE TABLE IF NOT EXISTS documents (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    document_type VARCHAR(100),
    file_name VARCHAR(255) NOT NULL,
    original_name VARCHAR(255),
    file_path TEXT NOT NULL,
    file_size BIGINT,
    content_type VARCHAR(150),
    is_required BOOLEAN NOT NULL DEFAULT FALSE,
    approval_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    rejection_reason TEXT,
    approved_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    approval_date TIMESTAMP,
    upload_date TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS evaluations (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    evaluator_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    evaluation_type VARCHAR(60) NOT NULL,
    subject VARCHAR(60),
    educational_level VARCHAR(60),
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    evaluation_date TIMESTAMP,
    score NUMERIC(10,2),
    max_score NUMERIC(10,2),
    recommendations TEXT,
    observations TEXT,
    cancellation_reason TEXT,
    interview_data JSONB,
    family_interview_score NUMERIC(10,2),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS interviews (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    interviewer_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    second_interviewer_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    interview_type VARCHAR(60) NOT NULL,
    scheduled_date DATE,
    scheduled_time TIME,
    duration INTEGER NOT NULL DEFAULT 60,
    location VARCHAR(255),
    mode VARCHAR(50) DEFAULT 'PRESENTIAL',
    status VARCHAR(40) NOT NULL DEFAULT 'SCHEDULED',
    notes TEXT,
    summary_sent BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS interviewer_schedules (
    id BIGSERIAL PRIMARY KEY,
    interviewer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day_of_week VARCHAR(20),
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    year INTEGER NOT NULL,
    specific_date DATE,
    schedule_type VARCHAR(40) NOT NULL DEFAULT 'RECURRING',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS notifications (
    id BIGSERIAL PRIMARY KEY,
    recipient_type VARCHAR(60),
    recipient_id BIGINT,
    recipient VARCHAR(255) NOT NULL,
    channel VARCHAR(30) NOT NULL,
    type VARCHAR(80),
    subject VARCHAR(255),
    message TEXT,
    template_name VARCHAR(100),
    template_data JSONB,
    status VARCHAR(30) NOT NULL DEFAULT 'SENT',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

INSERT INTO users (first_name, last_name, email, password_hash, role, subject, active, email_verified)
SELECT 'Admin', 'MTN', 'admin@mtn.cl', '$2a$10$DeDuj7.KlPffIIfL9Wv6q.3Yl5m4plfH8vG5UpiRaY1oWXcnZ6FZ2', 'ADMIN', 'GENERAL', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'admin@mtn.cl');
