---
name: new-service
description: Crear un módulo Maven de app Spring Boot de OrderFlow (servicio de dominio, gateway, config o discovery) con estructura, config, seguridad, observabilidad, Dockerfile y base de tests.
---

# Crear un módulo de app

Antes de empezar, confirma en SPEC §3.1 que el módulo existe, su puerto y su número de instancias, y en SPEC §2 sus dependencias. No agregues otras.

## Estructura

```
<modulo>/
  pom.xml                      # parent = pom raíz; solo dependencias de SPEC §2
  Dockerfile
  src/main/java/com/orderflow/<pkg>/
    <Nombre>Application.java
    api/          # controllers + DTO (records)
    domain/       # entidades, repositorios, servicios
    messaging/    # listeners y handlers (si consume)
    config/       # security, clientes, beans
  src/main/resources/
    application.yml
    logback-spring.xml
    db/migration/V1__init.sql  # tablas del dominio
    db/seed/R__seed.sql        # solo si SPEC §16 define datos
  src/test/java/com/orderflow/<pkg>/
    AbstractIT.java
```

## application.yml (solo esto; todo lo demás va en `config-repo/<modulo>.yml`)

```yaml
spring:
  application:
    name: inventory-service
  config:
    import: "configserver:"
  cloud:
    config:
      uri: ${CONFIG_SERVER_URL:http://config-server:8888}
      fail-fast: true
```

## Qué va en `config-repo/<modulo>.yml`
- `server.port` (SPEC §3.1), datasource (`jdbc:postgresql://postgres:5432/<base>`, usuario y contraseña por variables de entorno), `spring.flyway.locations: classpath:db/migration,classpath:db/messaging` (la segunda solo si usa messaging-lib).
- En `application-local.yml` y `application-e2e.yml` se agrega `classpath:db/seed` a las locations.
- Hikari `connection-timeout: 2000` (SPEC §12).
- Eureka: `eureka.client.service-url.defaultZone: http://discovery-server:8761/eureka`, `eureka.instance.prefer-ip-address: true`.
- Tracing: `management.tracing.sampling.probability: 1.0`, `management.otlp.tracing.endpoint: http://jaeger:4318/v1/traces`.
- Kafka (si aplica): `spring.kafka.bootstrap-servers: kafka:9092`, propiedades de productor de SPEC §7.1, `spring.kafka.template.observation-enabled: true`, `spring.kafka.listener.observation-enabled: true`.
- Exposición: `management.endpoints.web.exposure.include: health,info,prometheus`.

## Seguridad (servicios de dominio)
- Resource server JWT: `jwk-set-uri` = `http://keycloak:8180/realms/orderflow/protocol/openid-connect/certs`. Validador de `iss` = `http://localhost:8180/realms/orderflow` (SPEC §4).
- Converter: `realm_access.roles` → `ROLE_x`, más los `SCOPE_x` por defecto.
- `/actuator/health/**` y `/actuator/prometheus` sin autenticación (red interna). Todo lo demás según la matriz de SPEC §4.
- `companyId` del comprador: siempre del claim `company_id`. Expónlo con un único componente (`CurrentCompany`) que use todo el módulo.

## Errores
Un `@RestControllerAdvice` que devuelve `ProblemDetail` con propiedad `code` de SPEC §5.4. Validación de Bean Validation → 400 `VALIDATION_ERROR`.

## Logs
`logback-spring.xml` con `LogstashEncoder` a stdout. MDC: `traceId` y `spanId` (Micrometer), y `companyId`, `orderId`, `messageId` donde aplique.

## Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/*.jar app.jar
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
```

## Compose
Agrega el servicio a `deploy/docker-compose.yml` en el perfil correcto (SPEC §3.2): `mem_limit: 512m`, `SPRING_PROFILES_ACTIVE=${ORDERFLOW_PROFILE}`, healthcheck sobre `/actuator/health`, `depends_on` con `service_healthy` (config-server, discovery-server, postgres y kafka según use). Sin puertos al host salvo los que SPEC indica. catalog-service con `deploy.replicas: 2`.

## AbstractIT

```java
@SpringBootTest(properties = {
    "spring.cloud.config.enabled=false",
    "eureka.client.enabled=false"
})
@Testcontainers
public abstract class AbstractIT {
  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
  // Kafka / LocalStack solo si el módulo los usa
}
```
La configuración que vive en `config-repo` y el test necesita va en `src/test/resources/application.yml`. JWT en tests: `spring-security-test` (`jwt().jwt(j -> j.claim("company_id","ACME")).authorities(...)`).
