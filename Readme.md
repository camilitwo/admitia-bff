# admitia-bff

Monolito Java Spring Boot que consolida las APIs documentadas en `../API_REFERENCE.md` para que el frontend se conecte directamente a `http://localhost:8080` sin pasar por la arquitectura distribuida anterior.

## Stack
- Java 21
- Spring Boot 3.4
- PostgreSQL
- Flyway
- JWT stateless
- Uploads locales en `uploads/`

## Ejecutar
```bash
cd admitia-bff
mvn spring-boot:run
```

## Variables relevantes
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `APP_JWT_SECRET`
- `APP_UPLOADS_DIR`
- `APP_EMAIL_MOCK_MODE`
- `APP_APPLICATION_REMINDERS_ENABLED` (`false` por defecto)
- `APP_APPLICATION_REMINDERS_ACADEMIC_YEAR` (`2027` por defecto)
- `APP_APPLICATION_REMINDERS_CRON` (`0 0 11 * * MON,THU` por defecto)
- `APP_APPLICATION_REMINDERS_ZONE` (`America/Santiago` por defecto)
- `PORT`

## Recordatorios de postulación

El BFF puede enviar recordatorios durables de pago y formulario familiar los lunes y jueves a las 11:00 hora de Chile. El flujo está apagado por defecto; para producción configura:

```text
APP_APPLICATION_REMINDERS_ENABLED=true
APP_APPLICATION_REMINDERS_ACADEMIC_YEAR=2027
APP_APPLICATION_REMINDERS_CRON=0 0 11 * * MON,THU
APP_APPLICATION_REMINDERS_ZONE=America/Santiago
APP_APPLICATION_REMINDERS_BATCH_SIZE=50
APP_APPLICATION_REMINDERS_MAX_ATTEMPTS=6
APP_FRONTEND_BASE_URL=https://admitia.cl
APP_EMAIL_MOCK_MODE=false
```

Los intentos y resultados se auditan en `application_reminder_deliveries`. Las inconsistencias de pago externo quedan como `SKIPPED` con `last_error = 'PAYMENT_STATUS_INCONSISTENT'` y nunca generan un correo incorrecto.

La configuración y el orden de despliegue del módulo aislado están documentados en
[`PREKINDER_DEPLOYMENT.md`](PREKINDER_DEPLOYMENT.md). El módulo permanece apagado por
defecto y no modifica ni reutiliza migraciones legacy.

## Notas
- El servicio escucha en `8080` por defecto para mantener compatibilidad con el frontend.
- Las rutas expuestas conservan los prefijos `/api/auth`, `/api/users`, `/api/applications`, `/api/students`, `/api/documents`, `/api/evaluations`, `/api/interviews`, `/api/interviewer-schedules`, `/api/notifications`, `/api/email`, `/api/institutional-emails`, `/api/guardians`, `/api/dashboard` y `/api/analytics`.
