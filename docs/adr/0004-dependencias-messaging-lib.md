# 0004 — Dependencias que faltan en SPEC §2 para messaging-lib

- **Estado:** Propuesto
- **Fecha:** 2026-11-06
- **Fase / ítem donde surgió:** Fase 1, "messaging-lib: outbox" y F09

## Problema
`SPEC §2` dice "cualquier otra requiere ADR", y la lista de `messaging-lib` más la fila "Tests" no alcanza para dos cosas que SPEC y FAILURES piden en ese módulo.

**1. F09 no se puede ejecutar.** `FAILURES F09` fija el nivel "IT messaging-lib". `SPEC §14` y `CLAUDE.md` exigen para los IT "`@SpringBootTest` + Testcontainers (`@ServiceConnection`)". Resuelto con `dependency:tree` sobre un pom de `messaging-lib` que tiene solo lo permitido, el classpath de test no trae:

| Falta | Para qué hace falta |
|---|---|
| `postgresql` | Sin driver JDBC no hay conexión a Postgres. La fila "Tests" solo lo permite en `e2e-tests` |
| `spring-boot-starter-jdbc` | `DataSource`, Hikari, `JdbcTemplate`, gestor de transacciones y `JdbcConnectionDetails` para `@ServiceConnection` |
| `spring-boot-kafka` | `KafkaTemplate` autoconfigurado y `KafkaConnectionDetails` para `@ServiceConnection` |
| `spring-boot-flyway`, `flyway-core`, `flyway-database-postgresql` | Ejecutar `db/messaging/V0_1__outbox.sql` por la misma vía que los servicios (skill `outbox`) |

Los servicios de dominio ya traen todo esto, pero `messaging-lib` no, y su IT corre dentro del módulo.

**2. El endpoint de replay no compila.** `SPEC §5.5` dice "Lo implementa messaging-lib en cada servicio consumidor", y la skill `kafka-consumer` lo ubica en la librería. `@RestController` y `@PostMapping` están en `spring-web`, que no está en la lista de `messaging-lib` ni llega de forma transitiva. Es el tercer ítem de la Fase 1.

Sin esta decisión no se pueden cerrar F09 ni el ítem de inbox, DLT y replay. Bloquea la Fase 1.

## Opciones (máximo 2)
1. **Completar la lista de §2.** Las seis dependencias de la tabla, solo en scope `test`, y `spring-web` como `<optional>`, igual que SQS. No cambia lo que llega a los servicios: ya tienen `spring-web` por `spring-boot-starter-web`. F09 queda donde FAILURES lo pide.
2. **Mover F09 a un servicio.** F09 pasaría a "IT order", en Fase 2, cuando exista `order-service`. Evita las seis dependencias de test, pero cambia FAILURES y PHASES, deja la Fase 1 sin ninguna prueba del outbox y construye `order-service` sobre una librería sin probar. `spring-web` se necesita igual.

## Recomendación
Opción 1. No cambia contratos ni F, todas las versiones las gestiona el BOM de Boot 4.0.9 y lo que se agrega en scope `test` no llega a producción. La opción 2 retrasa una fase la primera prueba del componente del que depende toda la saga.

## Cobertura en dos niveles
Con la opción 1, el outbox se prueba en la librería y también integrado en un microservicio, sin agregar ningún F:
- **Librería:** F09 (IT messaging-lib, Fase 1) prueba la garantía aislada.
- **Microservicio:** F06 (IT inventory, Fase 3) exige que el StockReserved llegue a `inventory.replies`, F19 (IT order, Fase 3) cuenta las emisiones de ReleaseCredit y F11 (E2E, Fase 3) revisa el outbox de `orders` con Kafka caído.

## Verificación
Spike desechable fuera del repo, 2026-11-06: `@SpringBootTest` con `@ServiceConnection` sobre `postgres:16-alpine` y `apache/kafka:3.9.1`, usando las seis dependencias en scope `test`. Pasó: migración Flyway de una tabla con `JSONB`, lectura con `JdbcTemplate` y envío real con `KafkaTemplate`.

Límites de esa prueba: no usó las imágenes de compose (`postgres:16.15-alpine`, `apache/kafka:4.4.0`), que el IT real sí usará. `spring-web` no se probó: se dedujo de que no aparece en el `dependency:tree`.

## Nota sobre F09 (no requiere cambios)
La skill `outbox` dice "publisher deshabilitado (propiedad de test)", pero una propiedad nueva `orderflow.*` chocaría con la regla 1 de `CLAUDE.md`, porque SPEC §15 no la lista. El test no la necesita: inserta la fila en `@BeforeAll`, antes de que arranque el contexto de Spring, y el publisher arranca con el contexto. Eso cumple la fila de F09 ("con el publisher detenido; arrancar el publisher"). El spike confirmó que ese `@BeforeAll` corre antes del contexto.

## Cambios exactos si se acepta

**Con la opción 1**, en `docs/SPEC.md` §2:
- Fila "messaging-lib": reemplazar `spring-cloud-aws-starter-sqs como dependencia **`<optional>`** (la autoconfiguración SQS solo se activa si el servicio la trae)` por `spring-cloud-aws-starter-sqs y spring-web como dependencias **`<optional>`** (la autoconfiguración SQS solo se activa si el servicio la trae; spring-web es para el endpoint de replay, §5.5)`.
- Fila "Tests": agregar al final `; en messaging-lib además postgresql, spring-boot-starter-jdbc, spring-boot-kafka, spring-boot-flyway, flyway-core, flyway-database-postgresql`.

**Con la opción 2:**
- `docs/SPEC.md` §2, fila "messaging-lib": el mismo cambio de `spring-web`.
- `docs/FAILURES.md`, fila F09: Nivel `IT order`, Fase `2`.
- `docs/PHASES.md`: mover `F09` de la Fase 1 a la Fase 2.
- Skill `outbox`, sección "Test F09": cambiar "IT messaging-lib" por "IT order".

---
Reglas: un ADR solo **corrige** una contradicción o **elimina** algo. No agrega alcance; eso va a BACKLOG.md.
Lo acepta el humano, que aplica los cambios a los archivos protegidos y cambia el estado.
