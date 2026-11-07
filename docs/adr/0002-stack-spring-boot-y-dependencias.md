# 0002 — Versión de Spring Boot y lista de dependencias de SPEC §2

- **Estado:** Aceptado (opción 1, decisión del humano, 2026-11-06)
- **Fecha:** 2026-11-06
- **Fase / ítem donde surgió:** Fase 0, "Pom raíz multi-módulo + wrapper; versiones fijadas según SPEC §2"

## Problema
`SPEC §2` pide "la versión GA más reciente de Spring Boot que sea compatible a la vez con un release train de Spring Cloud y con Spring Cloud AWS". Verificado en Maven Central y en el README de Spring Cloud AWS el 2026-11-06:

| Componente | Resultado |
|---|---|
| Spring Boot 4.1.2 | GA, pero sin train compatible: Spring Cloud 2026.0.0 sigue en RC2 |
| Spring Boot 4.0.9 | Es lo que declara Spring Cloud 2025.1.4 |
| Spring Cloud AWS 4.x | Matriz oficial: Spring Cloud 2025.1.x, Boot 4.0.x. Última: 4.2.1 |

La regla da **Boot 4.0.9 + Spring Cloud 2025.1.4 + Spring Cloud AWS 4.2.1**. Pero la lista de dependencias de la misma sección, que no admite otras sin ADR, no funciona con esa versión:

1. **No existe:** `spring-cloud-starter-gateway` (gateway) no está publicado en 5.0.4, la versión del train 2025.1.4. Su sucesor es `spring-cloud-starter-gateway-server-webflux`.
2. **Flyway no se autoconfigura:** `FlywayAutoConfiguration` ya no está en `spring-boot-autoconfigure`, sino en `spring-boot-flyway`. Con solo `flyway-core` las migraciones no corren.
3. **Kafka no se autoconfigura:** `KafkaAutoConfiguration` está en `spring-boot-kafka`. Con solo `spring-kafka` no hay `KafkaTemplate` ni listeners autoconfigurados.
4. **Tracing OTLP:** la autoconfiguración está en módulos aparte (`spring-boot-micrometer-tracing-opentelemetry`, `spring-boot-opentelemetry`), que §2 no lista. Sin ellos no hay trazas (afecta a §13 y F18).

Además hay un hueco que existe **con cualquiera de las dos opciones**: `@ServiceConnection`, obligatorio según `SPEC §14` y `CLAUDE.md`, vive en `spring-boot-testcontainers`, que §2 no lista en Tests.

Sin esta decisión no se puede escribir el pom raíz.

## Opciones (máximo 2)
1. **Boot 4.0.9 + Spring Cloud 2025.1.4 + Spring Cloud AWS 4.2.1**, corrigiendo la lista de §2. Respeta la regla de versiones. Cuesta adaptar lo que cambia en Boot 4: Testcontainers pasa a 2.0.5 y los artefactos de test están modularizados.
2. **Boot 3.5.16 + Spring Cloud 2025.0.x + Spring Cloud AWS 3.4.2**, corrigiendo la regla de versiones de §2. La lista de dependencias queda igual (salvo `spring-boot-testcontainers`). Se aleja de la versión vigente y Spring Cloud AWS 3.4.x es la última línea para Boot 3.5. Un reporte en las discusiones de awspring indica que un CVE que afecta a 3.0.0–3.4.2 solo se corrigió en 4.0.2. No se verificó contra el advisory.

## Recomendación
Opción 1. Es lo que SPEC pide y los cambios son acotados: un artefacto renombrado y módulos agregados, sin tocar contratos, APIs ni F. La opción 2 arrancaría el proyecto sobre una línea sin continuidad y con un posible CVE sin parche.

## Lista verificada de artefactos (opción 1)
Método, 2026-11-06: pom temporal con parent `spring-boot-starter-parent:4.0.9` que importa `spring-cloud-dependencies:2025.1.4` y `spring-cloud-aws-dependencies:4.2.1`. Se leyó el `dependencyManagement` efectivo y se resolvió la lista completa con `dependency:resolve` (400 artefactos, sin errores). Esto prueba que existen y resuelven juntos, **no** que funcionen en ejecución: aún no se compiló ni arrancó ningún módulo.

**Gestionados por los BOM (sin versión en los módulos):**

| groupId | artifactId | Versión |
|---|---|---|
| org.springframework.boot | spring-boot-starter-web, -data-jpa, -validation, -security, -oauth2-resource-server, -oauth2-client, -actuator, -data-redis-reactive, -test | 4.0.9 |
| org.springframework.boot | spring-boot-flyway, spring-boot-kafka, spring-boot-micrometer-tracing-opentelemetry, spring-boot-opentelemetry, spring-boot-testcontainers, spring-boot-webmvc-test | 4.0.9 |
| org.springframework.cloud | spring-cloud-starter-config, spring-cloud-config-server | 5.0.6 |
| org.springframework.cloud | spring-cloud-starter-netflix-eureka-client, spring-cloud-starter-netflix-eureka-server | 5.0.3 |
| org.springframework.cloud | spring-cloud-starter-loadbalancer | 5.0.4 |
| org.springframework.cloud | spring-cloud-starter-circuitbreaker-resilience4j | 5.0.3 |
| org.springframework.cloud | spring-cloud-starter-gateway-server-webflux | 5.0.4 |
| io.awspring.cloud | spring-cloud-aws-starter-sqs | 4.2.1 |
| org.springframework.kafka | spring-kafka | 4.0.8 |
| org.springframework | spring-jdbc | 7.0.10 |
| org.flywaydb | flyway-core, flyway-database-postgresql | 11.14.1 |
| org.postgresql | postgresql | 42.7.14 |
| io.micrometer | micrometer-registry-prometheus, micrometer-core | 1.16.8 |
| io.micrometer | micrometer-tracing-bridge-otel, micrometer-tracing | 1.6.8 |
| io.opentelemetry | opentelemetry-exporter-otlp | 1.55.0 |
| tools.jackson.core | jackson-databind (Jackson 3, el de Boot 4) | 3.1.7 |
| com.fasterxml.jackson.core | jackson-databind (Jackson 2) | 2.21.7 |
| software.amazon.awssdk | s3, sqs | 2.57.0 |
| org.springframework.security | spring-security-test | 7.0.8 |
| org.awaitility | awaitility | 4.3.0 |
| org.wiremock | wiremock-standalone | 3.13.2 |
| org.testcontainers | testcontainers-junit-jupiter, testcontainers-postgresql, testcontainers-kafka, testcontainers-localstack | 2.0.5 |

**Sin gestión en ningún BOM (versión explícita en el pom raíz, última GA):**

| groupId | artifactId | Versión |
|---|---|---|
| net.logstash.logback | logstash-logback-encoder (usa Jackson 3) | 9.0 |
| io.rest-assured | rest-assured | 6.3.0 |
| com.amazonaws | aws-lambda-java-core | 1.4.1 |
| com.amazonaws | aws-lambda-java-events | 3.16.1 |
| org.apache.pdfbox | pdfbox | 3.0.8 |

`maven-shade-plugin` lo gestiona el parent de Boot (3.6.2).

**Hallazgos de esta verificación que el ADR no tenía:**
- Testcontainers 2.x renombró sus módulos: `junit-jupiter`, `postgresql`, `kafka` y `localstack` pasan a llevar el prefijo `testcontainers-`.
- "jackson" en §2 es ambiguo porque hay dos líneas. messaging-lib usa Jackson 3 para coincidir con Boot 4; las Lambdas, sin Spring, siguen en Jackson 2.
- WireMock se fija como `wiremock-standalone`, el artefacto que gestiona el BOM.
- `spring-boot-starter-web`, `-oauth2-resource-server` y `-oauth2-client` están **deprecados** en 4.0.9 en favor de `-webmvc`, `-security-oauth2-resource-server` y `-security-oauth2-client`. Funcionan y se mantienen para no ampliar este ADR.

## Cambios exactos si se acepta

**Con la opción 1**, en `docs/SPEC.md` §2:
- Línea "Versiones": agregar al final `Fijadas: Spring Boot 4.0.9, Spring Cloud 2025.1.4, Spring Cloud AWS 4.2.1. Las dependencias fuera de los BOM se fijan en el pom raíz: logstash-logback-encoder 9.0, rest-assured 6.3.0, aws-lambda-java-core 1.4.1, aws-lambda-java-events 3.16.1, pdfbox 3.0.8.`
- Fila "Todos los servicios de dominio": agregar `spring-boot-flyway, spring-boot-kafka, spring-boot-micrometer-tracing-opentelemetry, spring-boot-opentelemetry`.
- Fila "gateway": reemplazar `spring-cloud-starter-gateway (variante reactiva)` por `spring-cloud-starter-gateway-server-webflux`, y agregar `spring-boot-micrometer-tracing-opentelemetry, spring-boot-opentelemetry`.
- Fila "messaging-lib": reemplazar `jackson` por `jackson-databind de tools.jackson.core (Jackson 3)`.
- Fila "Lambdas": reemplazar `jackson-databind` por `jackson-databind de com.fasterxml.jackson.core (Jackson 2)`.
- Fila "Tests": reemplazar `testcontainers (junit-jupiter, postgresql, kafka, localstack)` por `testcontainers-junit-jupiter, testcontainers-postgresql, testcontainers-kafka, testcontainers-localstack`, reemplazar `wiremock` por `wiremock-standalone`, y agregar `spring-boot-testcontainers, spring-boot-webmvc-test`.

**Con la opción 2**, en `docs/SPEC.md` §2:
- Reemplazar la línea "Versiones" por `**Versiones:** Spring Boot 3.5.16, Spring Cloud 2025.0.x (último parche), Spring Cloud AWS 3.4.2. Luego no se cambian.`
- Fila "Tests": agregar `spring-boot-testcontainers`.

No cambian FAILURES, PHASES ni las skills con ninguna de las dos. Con la opción 1, si el snippet de `AbstractIT` de la skill `new-service` no compila con Testcontainers 2.x, se tratará como detalle del test base; aún no se probó.

---
Reglas: un ADR solo **corrige** una contradicción o **elimina** algo. No agrega alcance; eso va a BACKLOG.md.
Lo acepta el humano, que aplica los cambios a los archivos protegidos y cambia el estado.
