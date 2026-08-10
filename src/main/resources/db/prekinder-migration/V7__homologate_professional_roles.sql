-- Homologa perfiles profesionales con los roles operativos reales del flujo Prekínder.
ALTER TABLE professional_profiles
    DROP CONSTRAINT IF EXISTS professional_profiles_role_code_check;

ALTER TABLE professional_profiles
    ADD CONSTRAINT professional_profiles_role_code_check CHECK (role_code IN (
        'ADMIN', 'COORDINATOR', 'EVALUATOR',
        'PK_ADMIN', 'PK_COORDINATOR', 'PK_RECEPTION', 'PK_DATA_ENTRY',
        'PK_REVIEWER', 'PK_COMMITTEE', 'PK_FINAL_APPROVER', 'PK_AUDITOR',
        'PK_EVALUATOR_ACADEMIC', 'PK_EVALUATOR_PSYCHOMOTOR',
        'PK_EVALUATOR_PSYCHOLOGY', 'PK_EVALUATOR_ENTRY_INDICATORS',
        'PK_EVALUATOR_GROUP_OBSERVATION', 'PK_EVALUATOR_LEARNING_SUPPORT',
        'PK_EVALUATOR_DAP'
    ));
