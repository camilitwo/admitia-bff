-- Ajusta únicamente procesos Prekínder publicados y actualmente vigentes.
-- La condición sobre required_documents vuelve la migración idempotente y no
-- modifica procesos históricos, futuros ni el modelo legacy.
WITH updated AS (
    UPDATE prekinder_process_configuration config
       SET required_documents = '["BIRTH_CERTIFICATE"]'::jsonb,
           version = config.version + 1,
           updated_at = now()
      FROM admission_processes process
     WHERE process.process_id = config.process_id
       AND process.status = 'PUBLISHED'
       AND (process.starts_at IS NULL OR process.starts_at <= now())
       AND (process.ends_at IS NULL OR process.ends_at >= now())
       AND config.required_documents <> '["BIRTH_CERTIFICATE"]'::jsonb
    RETURNING config.*
)
INSERT INTO prekinder_process_configuration_versions(
    configuration_version_id, process_id, version, snapshot
)
SELECT gen_random_uuid(), updated.process_id, updated.version,
       jsonb_build_object(
           'paymentEnabled', updated.payment_enabled,
           'paymentAmount', updated.payment_amount,
           'paymentCurrency', updated.payment_currency,
           'paymentGlosa', updated.payment_glosa,
           'paymentDueDays', updated.payment_due_days,
           'inclusionEnabled', updated.inclusion_enabled,
           'inclusionDocumentsRequired', updated.inclusion_documents_required,
           'minimumAgeMonths', updated.minimum_age_months,
           'maximumAgeMonths', updated.maximum_age_months,
           'ageReferenceDate', updated.age_reference_date,
           'applicantWeight', updated.applicant_weight,
           'familyWeight', updated.family_weight,
           'schemaVersion', updated.configuration_schema_version,
           'totalSeats', updated.total_seats,
           'maleSeats', updated.male_seats,
           'femaleSeats', updated.female_seats,
           'incorporationFeeAmount', updated.incorporation_fee_amount,
           'incorporationFeeCurrency', updated.incorporation_fee_currency,
           'incorporationFeeGlosa', updated.incorporation_fee_glosa,
           'requiredDocuments', updated.required_documents,
           'resultChannel', updated.result_channel
       )
  FROM updated
ON CONFLICT (process_id, version) DO NOTHING;
