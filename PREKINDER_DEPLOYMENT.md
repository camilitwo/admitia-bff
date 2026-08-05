# Despliegue seguro de Prekínder

## Aislamiento

Prekínder usa un `HikariDataSource`, `NamedParameterJdbcTemplate` y
`JdbcTransactionManager` propios. El JPA y Flyway legacy permanecen intactos. El paquete
`cl.mtn.admitiabff.prekinder` no contiene repositorios hacia la base legacy; sólo proyecta
el identificador y rol del usuario autenticado en `actors`.

La base nueva comienza vacía. No existe backfill ni migración de postulaciones. `V1` crea
la estructura nueva desde cero y sólo puede ejecutarse mediante el artefacto clasificado
`admitia-bff-0.0.1-SNAPSHOT-prekinder-db-migrator.jar` en `preDeployCommand`.

El migrador:

- exige `PREKINDER_MIGRATOR_DATASOURCE_URL` JDBC PostgreSQL;
- rechaza una URL igual a `SPRING_DATASOURCE_URL`;
- exige `PREKINDER_DATABASE_ID=admitia-prekinder`, sin depender del nombre `railway` asignado por Railway;
- usa credenciales DDL diferentes de las credenciales runtime;
- crea o actualiza el rol runtime, le revoca DDL y le concede sólo DML sobre el esquema;
- verifica el marcador `schema_metadata` después de aplicar Flyway;
- tiene `clean` deshabilitado;
- no inicia Spring ni carga el datasource legacy.

Las migraciones legacy (`classpath:db/migration`) y Prekínder
(`classpath:db/prekinder-migration`) viven en bases y tablas de historial Flyway diferentes.
Por ello ambos conjuntos comienzan correctamente en `V1`.

## Recursos Railway

Crear, en la misma región del BFF:

1. PostgreSQL privado `admitia-prekinder` con PITR activo.
2. Redis privado exclusivo para tickets, rate limits, revocación y fan-out mínimo.
3. Sin dominio público, TCP Proxy ni variables compartidas con otros servicios.
4. Usuario DDL sólo para pre-deploy y usuario de aplicación sin `CREATE`, `ALTER` ni `DROP`.

El migrador usa `PREKINDER_MIGRATOR_DATASOURCE_*`. El BFF usa
`PREKINDER_DATASOURCE_*` con el rol `prekinder_app`, creado por el propio pre-deploy.

## Variables del BFF

```text
PREKINDER_ENABLED=true
PREKINDER_DATABASE_ID=admitia-prekinder
PREKINDER_MIGRATOR_DATASOURCE_URL=jdbc:postgresql://...railway.internal:5432/railway
PREKINDER_MIGRATOR_DATASOURCE_USERNAME=<usuario propietario Railway>
PREKINDER_MIGRATOR_DATASOURCE_PASSWORD=<secreto propietario Railway>
PREKINDER_DATASOURCE_URL=jdbc:postgresql://...railway.internal:5432/railway
PREKINDER_DATASOURCE_USERNAME=prekinder_app
PREKINDER_DATASOURCE_PASSWORD=<secreto runtime diferente, mínimo 24 caracteres>
PREKINDER_REDIS_URL=redis://default:...@...railway.internal:6379
PREKINDER_ALLOWED_ORIGINS=https://admitia.cl

PREKINDER_ENCRYPTION_ACTIVE_VERSION=V1
PREKINDER_ENCRYPTION_KEY_V1=<32 bytes aleatorios codificados Base64>

PREKINDER_MTLS_ENFORCED=true
PREKINDER_MTLS_PORT=8443
PREKINDER_MTLS_KEY_STORE_B64=<PKCS12 del servidor codificado Base64>
PREKINDER_MTLS_KEY_STORE_PASSWORD=...
PREKINDER_MTLS_TRUST_STORE_B64=<PKCS12 con CA cliente codificado Base64>
PREKINDER_MTLS_TRUST_STORE_PASSWORD=...
```

Las claves deben ser diferentes en desarrollo, staging y producción. Respaldar la KEK de
producción fuera de Railway. Nunca imprimir variables, tickets, cuerpos, cookies o texto
cifrado durante smoke tests.

## Certificados

- CA privada de corta jerarquía y acceso restringido.
- Certificado servidor BFF con SAN igual a `PREKINDER_BFF_TLS_SERVER_NAME` de NGINX.
- Certificado cliente exclusivo para NGINX, con propósito clientAuth.
- Keystore/truststore BFF en PKCS12; NGINX usa PEM codificado Base64.
- Alertar antes de la expiración y probar rotación con solapamiento de certificados.

## Orden

1. Crear PostgreSQL/Redis privados y credenciales separadas.
2. Activar PITR y configurar secretos de cifrado y mTLS.
3. Construir con Java 21 (`mvn package`).
4. Railway ejecuta el migrador clasificado en pre-deploy.
5. Desplegar BFF con `PREKINDER_ENABLED=true` y conector 8443.
6. Ejecutar regresión legacy.
7. Desplegar NGINX con origen exacto y material mTLS.
8. Probar WSS, ticket de un uso, escritura/ACK, delta y modo degradado.
9. Desplegar el frontend con `/prekinder`.

No ejecutar Flyway, DDL ni clientes SQL manualmente contra staging o producción.

## Puertas de aceptación

- `mvn test` verde en Java 21.
- `npm run build` verde.
- `nginx -t` verde en la imagen de deploy.
- Testcontainers y carga se ejecutan sólo en CI aislado.
- ZAP REST/WebSocket no encuentra rutas sin autenticación ni orígenes reflejados.
- Restauración PITR ensayada sin usar producción como destino.
- 500 sockets, 100 operaciones/s, ACK p95 <300 ms, fan-out p95 <500 ms.
