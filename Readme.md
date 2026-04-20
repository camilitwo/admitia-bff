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
- `PORT`

## Notas
- El servicio escucha en `8080` por defecto para mantener compatibilidad con el frontend.
- Las rutas expuestas conservan los prefijos `/api/auth`, `/api/users`, `/api/applications`, `/api/students`, `/api/documents`, `/api/evaluations`, `/api/interviews`, `/api/interviewer-schedules`, `/api/notifications`, `/api/email`, `/api/institutional-emails`, `/api/guardians`, `/api/dashboard` y `/api/analytics`.
