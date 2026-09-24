-- Entregas de agenda por apoderado, con plantillas versionadas para cada evento.
-- El correo continúa dentro de la identidad cifrada; aquí sólo se persiste el rol receptor.

ALTER TABLE notification_intents
    ADD COLUMN recipient_kind VARCHAR(16),
    ADD COLUMN processing_started_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_notification_recipient_kind
        CHECK (recipient_kind IS NULL OR recipient_kind IN ('FATHER', 'MOTHER'));

CREATE INDEX idx_schedule_notifications_recipient
    ON notification_intents(template_code, status, next_attempt_at, created_at)
    WHERE batch_id IS NULL AND recipient_kind IS NOT NULL;

INSERT INTO prekinder_communication_templates(
    communication_template_id, process_id, event_code, name
)
SELECT gen_random_uuid(), process.process_id, defaults.event_code, defaults.template_name
  FROM admission_processes process
 CROSS JOIN (VALUES
    ('SCHEDULE_RESCHEDULED', 'Jornada reagendada'),
    ('SCHEDULE_CANCELLED', 'Jornada cancelada')
 ) AS defaults(event_code, template_name)
ON CONFLICT (process_id, event_code) DO NOTHING;

INSERT INTO prekinder_communication_template_versions(
    communication_template_version_id, communication_template_id, version,
    status, subject, body_html, allowed_variables, published_at
)
SELECT gen_random_uuid(), template.communication_template_id, 1, 'PUBLISHED',
       CASE template.event_code
         WHEN 'SCHEDULE_RESCHEDULED' THEN 'Reagendamiento evaluación Prekínder'
         ELSE 'Cancelación evaluación Prekínder'
       END,
       CASE template.event_code
         WHEN 'SCHEDULE_RESCHEDULED' THEN
           '{{institutionalImage}}<h1>Evaluación reagendada</h1><p>Estimados apoderados:</p>' ||
           '<p>La evaluación de {{applicantName}} fue reagendada. Esta información reemplaza la cita anterior.</p>' ||
           '<p><strong>Fecha:</strong> {{scheduleDate}}<br><strong>Horario:</strong> {{startTime}} a {{endTime}} horas<br>' ||
           '<strong>Modalidad:</strong> {{modality}}<br><strong>Lugar:</strong> {{location}}<br>' ||
           '<strong>Grupo:</strong> {{groupCode}}<br><strong>Detalle:</strong> {{evaluationDetail}}</p>'
         ELSE
           '{{institutionalImage}}<h1>Evaluación cancelada</h1><p>Estimados apoderados:</p>' ||
           '<p>La evaluación de {{applicantName}} fue cancelada.</p>' ||
           '<p><strong>Cita original:</strong> {{scheduleDate}}, {{startTime}} a {{endTime}} horas<br>' ||
           '<strong>Modalidad:</strong> {{modality}}<br><strong>Lugar:</strong> {{location}}<br>' ||
           '<strong>Grupo:</strong> {{groupCode}}<br><strong>Detalle:</strong> {{evaluationDetail}}<br>' ||
           '<strong>Motivo:</strong> {{reason}}</p>'
       END,
       '["applicantName","processName","portalUrl","scheduleDate","startTime","endTime","modality","location","groupCode","evaluationDetail","reason","institutionalImage","institutionalImageUrl"]'::jsonb,
       now()
  FROM prekinder_communication_templates template
 WHERE template.event_code IN ('SCHEDULE_RESCHEDULED', 'SCHEDULE_CANCELLED')
   AND NOT EXISTS (
       SELECT 1 FROM prekinder_communication_template_versions version
        WHERE version.communication_template_id = template.communication_template_id
   );

-- La plantilla histórica de asignación pasa a contener el detalle completo.
UPDATE prekinder_communication_template_versions version
   SET status = 'SUPERSEDED'
  FROM prekinder_communication_templates template
 WHERE template.communication_template_id = version.communication_template_id
   AND template.event_code = 'SCHEDULE_ASSIGNED'
   AND version.status = 'PUBLISHED';

INSERT INTO prekinder_communication_template_versions(
    communication_template_version_id, communication_template_id, version,
    status, subject, body_html, allowed_variables, published_at
)
SELECT gen_random_uuid(), template.communication_template_id,
       coalesce(max(existing.version), 0) + 1, 'PUBLISHED',
       'Asignación evaluación Prekínder',
       '{{institutionalImage}}<h1>Evaluación programada</h1><p>Estimados apoderados:</p>' ||
       '<p>La evaluación de {{applicantName}} fue programada.</p>' ||
       '<p><strong>Fecha:</strong> {{scheduleDate}}<br><strong>Horario:</strong> {{startTime}} a {{endTime}} horas<br>' ||
       '<strong>Modalidad:</strong> {{modality}}<br><strong>Lugar:</strong> {{location}}<br>' ||
       '<strong>Grupo:</strong> {{groupCode}}<br><strong>Detalle:</strong> {{evaluationDetail}}</p>' ||
       '<p>Recomendamos llegar con anticipación al establecimiento.</p>',
       '["applicantName","processName","portalUrl","scheduleDate","startTime","endTime","modality","location","groupCode","evaluationDetail","reason","institutionalImage","institutionalImageUrl"]'::jsonb,
       now()
  FROM prekinder_communication_templates template
  LEFT JOIN prekinder_communication_template_versions existing
    ON existing.communication_template_id = template.communication_template_id
 WHERE template.event_code = 'SCHEDULE_ASSIGNED'
 GROUP BY template.communication_template_id;
