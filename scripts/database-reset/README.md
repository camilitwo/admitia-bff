# Limpieza de datos antes de puesta en marcha

Estos scripts corresponden exclusivamente al esquema PostgreSQL del monolito
`admitia-bff` (migraciones Flyway V1 a V19). No deben ejecutarse contra la
plataforma SaaS multitenant ni contra otro servicio.

## Qué conserva el modo recomendado

- Usuarios internos: `ADMIN`, `TEACHER`, `COORDINATOR`, `CYCLE_DIRECTOR`,
  `PSYCHOLOGIST` e `INTERVIEWER`.
- Disponibilidad de vacantes (`grade_availability`).
- Horarios y parejas de entrevistadores.
- Historial de migraciones Flyway.

Elimina apoderados, sesiones, postulaciones, estudiantes, familiares,
entrevistas, evaluaciones, documentos registrados, pagos, eventos de pago,
sincronizaciones con el colegio y notificaciones.

El modo total elimina además los usuarios internos, horarios y parejas, pero
obliga a conservar un administrador activo indicado por correo.

## Requisitos de seguridad

1. Detener el BFF y cualquier proceso que escriba en la base.
2. Confirmar que `DATABASE_URL` apunta a la base correcta.
3. Crear y verificar el respaldo.
4. Revisar la vista previa y los manifiestos antes de limpiar.
5. No continuar si existen pagos reales sin autorización explícita del
   responsable financiero.

Los scripts usan transacciones, bloqueos exclusivos y validaciones del esquema.
Ante cualquier error, PostgreSQL revierte toda la limpieza.

## Scripts SQL

- `01_snapshot.sql`: copia el estado actual al esquema `prelaunch_backup` de la
  misma base. No reemplaza un `pg_dump` externo, pero permite conservar una
  fotografía antes de limpiar.
- `02_preview.sql`: valida el esquema y muestra exactamente qué existe.
- `03_cleanup.sql`: limpieza transaccional con confirmación explícita. Editar la
  única fila de parámetros ubicada al inicio del archivo.
- `04_validate.sql`: comprueba que no queden datos operacionales y que exista un
  administrador activo.
- `05_drop_snapshot.sql`: opcional; elimina definitivamente la copia interna
  cuando la limpieza ya fue validada y existe el respaldo externo necesario.

## Ejecución recomendada

Ejecutar, en este orden, el contenido completo de `01_snapshot.sql`,
`02_preview.sql`, `03_cleanup.sql` y `04_validate.sql` desde el cliente SQL que
uses habitualmente. Los archivos son SQL PostgreSQL puro.

Antes de ejecutar `03_cleanup.sql`, cambiar su parámetro `confirmation` desde
`NO` a `RESET_ADMITIA_OPERATIONAL_DATA`. Por defecto usa `keep_staff = true`.

Si la vista previa muestra pagos con estado `PAID`, montos pagados o un cargo
institucional, el script aborta. Sólo después de revisar y autorizar esos datos
se puede cambiar `allow_financial_delete` a `true` en la fila de parámetros.

## Modo total: conservar sólo un administrador

El correo debe existir, estar activo y tener rol `ADMIN`. El script aborta si
no cumple esas condiciones.

En la fila de parámetros de `03_cleanup.sql`, usar `keep_staff = false` y
escribir el correo del administrador en `keep_admin_email`.

## Datos fuera de PostgreSQL

El snapshot genera dos tablas de manifiesto:

- `prelaunch_backup.document_file_manifest`: archivos locales o URLs de Vercel Blob que deben
  eliminarse por separado una vez validado el resultado.
- `prelaunch_backup.firebase_user_manifest`: UID de Firebase asociados a usuarios. Los apoderados
  eliminados de PostgreSQL deben revocarse o eliminarse también de Firebase si
  se requiere un arranque realmente limpio.

La limpieza SQL no borra archivos ni usuarios externos automáticamente. Hacerlo
sin un manifiesto revisado dejaría la operación sin una recuperación sencilla.

## Restauración

El snapshot queda en `prelaunch_backup` para inspección o una restauración
controlada. Como protección ante una pérdida completa de la base, se recomienda
también generar un `pg_dump` externo antes de ejecutar `03_cleanup.sql`.

Cuando todo esté validado, ejecutar opcionalmente `05_drop_snapshot.sql`
cambiando su confirmación a `DROP_PRELAUNCH_BACKUP`. Hasta entonces, los datos
eliminados siguen almacenados dentro de la base en el esquema de respaldo.
