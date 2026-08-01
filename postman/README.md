# Colección Postman - API de Admisión MTN v1

Esta colección valida el contrato de `API_Admision_Especificacion_v1.0.pdf` antes de habilitar la integración del BFF. Usa únicamente datos ficticios y nunca debe almacenar credenciales reales en Git.

## Preparación

1. Importar `MTN_Admision_API_v1.postman_collection.json` y `MTN_Admision_QA.postman_environment.json`.
2. Seleccionar el environment `MTN Admisión - QA`.
3. Completar `client_secret` en el valor actual de Postman, no en el archivo.
4. Cambiar los RUT ficticios si QA ya los tiene asociados a datos incompatibles.
5. Ejecutar las carpetas en orden. Las pruebas negativas están separadas y no forman parte del camino feliz.

El ambiente QA usa HTTP porque así lo define la especificación. No reutilizar esa excepción en producción.

## Orden y resultados esperados

1. `01 Autenticación`: obtiene y guarda el Bearer token; `ping` debe confirmar el scope `ADMISION`.
2. `02 Alta de admisión`: crea o reutiliza el apoderado y alumno. La repetición debe conservar los identificadores institucionales.
3. `03 Cobros`: crea un cobro, comprueba idempotencia, crea otro para una segunda postulación y consulta el estado.
4. Después de pagar manualmente mediante `payment_link`, cambiar `expect_paid=true` y ejecutar `Consultar estado después de pago manual`.
5. `04 Seguridad y errores`: ejecutar de forma aislada; sus tests esperan respuestas de error.

Las variables `due_date`, `external_reference` y `second_external_reference` se generan una vez por corrida. Para crear cobros nuevos, limpiar las referencias antes de volver a ejecutar la colección.

## Ejecución automatizada

```bash
newman run postman/MTN_Admision_API_v1.postman_collection.json \
  -e postman/MTN_Admision_QA.postman_environment.json \
  --env-var client_secret="$MTN_ADMISSION_CLIENT_SECRET" \
  --reporters cli,junit \
  --reporter-junit-export target/newman-mtn-admision.xml
```

## Matriz de salida QA

Completar esta tabla antes de habilitar la integración productiva.

| Verificación | Resultado QA | Observaciones |
|---|---|---|
| Autenticación HTTP Basic | Pendiente | |
| Autenticación mediante formulario | Pendiente | |
| Formato real de errores | Parcial | `GET /admision/ping` sin token respondió HTTP 401 con `error=unauthorized` y `error_description`. |
| Alta e idempotencia por RUT | Pendiente | |
| Comportamiento `PARCIAL` | Pendiente | Requiere preparar conflicto o alumno inválido. |
| Idempotencia de `referencia_externa` | Pendiente | Debe retornar el mismo `c_orderpayschedule_id`. |
| `link_pago` no vacío | Pendiente | La captura del PDF muestra una respuesta vacía y contradice la tabla formal. |
| Formato/zona de `fecha_pago` | Pendiente | La especificación muestra `yyyy-MM-dd HH:mm` sin zona. |
| Valor de `codCurso` | Pendiente | Confirmar que QA acepta exactamente los valores seleccionados en el formulario de postulación. |
| Consulta después del pago | Pendiente | Debe informar monto, fecha y transacción. |

La conectividad a QA fue comprobada el 21-07-2026: `GET http://erp.cmtn.cl:8888/api/admision/ping` respondió `401`, confirmando que el servicio está disponible y protegido. Las pruebas autenticadas siguen pendientes hasta disponer de `client_secret`.

## Criterio de aprobación

La colección se considera aprobada cuando el camino feliz pasa completo, el reintento devuelve el mismo cobro, el enlace es navegable y la consulta posterior informa `PAGADO`. Cualquier diferencia debe reflejarse aquí y en los DTOs del BFF antes de producción.

## Mapeo hacia el BFF

| Contrato institucional | Uso en Admitia |
|---|---|
| `/auth/token` | Token de máquina cacheado; nunca se expone al navegador. |
| `/admision/apoderados` | Alta idempotente al iniciar el pago de una postulación. |
| `/admision/cobros` | Genera una deuda con referencia estable `{prefix}-{applicationId}`. |
| `/admision/cobros/{id}` | Concilia pagos al entrar al home, consultar estado o continuar el pago. |

El BFF conserva sus endpoints públicos `/v1/payments/applications/{id}/checkout`, `/v1/payments/applications/{id}/status` y `/v1/applications/my-applications`; el frontend no consume directamente la API institucional.
