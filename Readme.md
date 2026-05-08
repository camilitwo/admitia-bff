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


## Deploy en Railway
1. Crea un proyecto nuevo en Railway y conecta este repositorio.
2. Railway detectará el `Dockerfile` y construirá la imagen automáticamente.
3. Agrega un servicio PostgreSQL desde Railway y configura estas variables en el servicio backend:
   - `SPRING_DATASOURCE_URL` (usa la URL interna de Railway, por ejemplo `jdbc:postgresql://<host>:<port>/<db>`)
   - `SPRING_DATASOURCE_USERNAME`
   - `SPRING_DATASOURCE_PASSWORD`
   - `APP_JWT_SECRET` (obligatorio en producción)
   - `APP_UPLOADS_DIR=/tmp/uploads`
   - `APP_EMAIL_MOCK_MODE=false` (si ya tienes integración real de correo)
4. Despliega. Railway inyecta `PORT` automáticamente y la app lo usa en `server.port`.

## Notas
- El servicio escucha en `8080` por defecto para mantener compatibilidad con el frontend.
- Las rutas expuestas conservan los prefijos `/api/auth`, `/api/users`, `/api/applications`, `/api/students`, `/api/documents`, `/api/evaluations`, `/api/interviews`, `/api/interviewer-schedules`, `/api/notifications`, `/api/email`, `/api/institutional-emails`, `/api/guardians`, `/api/dashboard` y `/api/analytics`.
