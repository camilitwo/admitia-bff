# Operación segura del cierre maestro de admisión

El cierre queda deshabilitado por defecto. La migración `V20` sólo crea tablas,
índices y el ciclo 2027; no modifica postulaciones ni produce envíos.

## Antes del despliegue

1. Crear un respaldo verificable de PostgreSQL.
2. Ejecutar `00_preflight_read_only.sql` y resolver todos sus hallazgos.
3. Probar Flyway sobre una copia representativa de producción.
4. Desplegar con estos flags en `false`:
   - `APP_ADMISSION_CLOSE_ENABLED=false`
   - `APP_ADMISSION_RESULT_DISPATCH_ENABLED=false`
5. Ejecutar `01_post_migration_verification.sql`. El conteo inicial de la cola
   debe ser cero.

`02_optional_academic_year_backfill.sql` es opcional. Por defecto falla con un
ID marcador y termina en `ROLLBACK`; exige reemplazar el marcador por una lista
explícita de postulaciones previamente revisadas.

## Activación controlada

Comprobar primero que `APP_EMAIL_MOCK_MODE=false`, `RESEND_API_KEY` está presente
y `APP_EMAIL_FROM` usa un dominio verificado. Luego:

1. Habilitar el trabajador: `APP_ADMISSION_RESULT_DISPATCH_ENABLED=true`.
2. Verificar que la API informe `dispatchEnabled=true` y `deliveryReady=true`.
3. Habilitar el botón: `APP_ADMISSION_CLOSE_ENABLED=true`.
4. Ejecutar el cierre desde una cuenta `ADMIN` y observar el progreso hasta
   `CLOSED` o `CLOSED_WITH_ERRORS`.

El interruptor de emergencia es
`APP_ADMISSION_RESULT_DISPATCH_ENABLED=false`. Detiene la reclamación de nuevos
lotes sin eliminar la cola. Los registros `SENT` nunca vuelven a procesarse.

## Recuperación

- `PUBLISHING`: el servidor continúa por lotes aunque se cierre el navegador.
- `CLOSED`: todos los destinatarios fueron confirmados por Resend.
- `CLOSED_WITH_ERRORS`: el botón sólo reintenta filas `FAILED`. Las filas
  `UNKNOWN` exigen revisión manual en Resend y nunca se reenvían automáticamente.
- No existe reapertura desde la interfaz y no debe cambiarse el estado del ciclo
  directamente en producción.
