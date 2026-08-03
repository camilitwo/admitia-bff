# Despliegue seguro de parejas Director de Ciclo + Psicólogo

La implementación es aditiva. La migración `V15` no elimina ni transforma entrevistas, evaluaciones, usuarios u horarios existentes. Las entrevistas históricas mantienen ambos entrevistadores y pueden incorporar el vínculo `interviewer_pair_id` mediante una normalización conservadora.

## Orden de despliegue

1. Respaldar PostgreSQL y desplegar el BFF con `APP_INTERVIEWER_PAIRS_ENFORCEMENT_ENABLED=false`.
2. Verificar que Flyway aplicó `V15__create_interviewer_pairs.sql`.
3. Desplegar el frontend y crear las parejas desde **Gestión de Usuarios → Parejas de entrevistas**.
4. Ejecutar el diagnóstico sin cambios:

   ```http
   POST /v1/interviewer-pairs/normalization?execute=false
   Authorization: Bearer <admin-token>
   ```

5. Revisar cada elemento `WOULD_LINK` y `SKIP`. El proceso solo vincula entrevistas cuyos dos integrantes históricos coinciden exactamente con una pareja activa y cuyo curso está cubierto.
6. Ejecutar la normalización confirmada:

   ```http
   POST /v1/interviewer-pairs/normalization?execute=true&confirmation=NORMALIZE_CYCLE_DIRECTOR_INTERVIEWS
   Authorization: Bearer <admin-token>
   ```

7. Repetir el `dry-run`. Debe devolver `linkable=0`; esto comprueba idempotencia.
8. Validar creación y reprogramación en staging. Luego configurar `APP_INTERVIEWER_PAIRS_ENFORCEMENT_ENABLED=true` y reiniciar el BFF.

## Garantías

- No se eliminan entrevistas ni evaluaciones.
- No se modifican entrevistas familiares.
- Editar integrantes o cursos archiva la revisión anterior y crea una nueva.
- Las entrevistas ya vinculadas conservan su revisión histórica.
- Una persona solo puede pertenecer a una pareja activa.
- Sin confirmación exacta, la normalización en modo ejecución es rechazada.

## Reversión

Si se detecta un problema, configurar `APP_INTERVIEWER_PAIRS_ENFORCEMENT_ENABLED=false`. Esto permite temporalmente el contrato anterior sin borrar las parejas ni los vínculos ya creados. No se debe revertir manualmente la migración ni eliminar las tablas mientras existan entrevistas vinculadas.
