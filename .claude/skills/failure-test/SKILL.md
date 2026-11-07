---
name: failure-test
description: Escribir los tests de docs/FAILURES.md (F00–F19), en nivel IT o E2E, con inyección de fallos, aserciones de efectos y sin debilitar el resultado esperado.
---

# Tests de fallos

La fila del F en `docs/FAILURES.md` es el contrato: preparación, resultado y límite. El test verifica **todo** el resultado esperado de la fila, no una parte.

## Reglas
1. Una clase por F: `F07InventoryDownE2E`, `F13ConcurrentLastUnitIT`. Nivel y módulo según la columna "Nivel".
2. Escribe primero el test y córrelo: debe fallar por la razón esperada (no por un error de compilación o de setup).
3. Esperas: Awaitility con el "Límite" de la fila (`await().atMost(Duration.ofSeconds(90))`). Nunca `Thread.sleep`. Para "esperar 25 s" usa `await().pollDelay(25, SECONDS).until(() -> true)`.
4. "Sin cambios" se verifica con deltas: medir antes y después.
5. Cada test que pausa o detiene un contenedor lo restaura en `@AfterEach`, aunque falle.
6. Si el resultado esperado no se puede cumplir con el diseño de SPEC: detente y ADR (CLAUDE.md regla 2). No cambies la aserción.

## IT (dentro del módulo)
- Base `AbstractIT` (skill `new-service`).
- Kafka: publica con `KafkaTemplate` real y headers `message_id`/`message_type`; lee con un consumer de test (group = UUID aleatorio, `auto.offset.reset=earliest`).
- Pausar un contenedor de Testcontainers (F10b):
  `postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();` y `unpauseContainerCmd` en `finally`.
- order-service: catalog y token endpoint con WireMock; las propiedades de LoadBalancer simple-discovery apuntan a WireMock (`spring.cloud.discovery.client.simple.instances.catalog-service[0].uri`).
- Timeouts cortos vía `@SpringBootTest(properties = …)`, por ejemplo F19: `orderflow.saga.compensation-retry-interval=PT1S`.

## E2E (`e2e-tests`)
Precondición: stack levantado con `ORDERFLOW_PROFILE=e2e` (CLAUDE.md, Comandos). La suite corre secuencial.

Utilidades (crear una sola vez en `e2e-tests/src/test/java/com/orderflow/e2e/support/`):
- `Tokens.buyer("acme")`, `Tokens.ops()`: password grant contra `http://localhost:8180/realms/orderflow/protocol/openid-connect/token`, client `orderflow-cli`; cache hasta expirar.
- `Api`: RestAssured con base `http://localhost:8080`. `createOrder(token, body)` siempre con `Idempotency-Key` = UUID nuevo salvo que el test fije uno.
- `Db`: JDBC a `localhost:5432` por base (`inventory`, `credit`, `shipping`, `orders`, `catalog`). Lecturas para deltas y preparación de datos (p. ej. F05).
- `Compose.pause(svc)`, `unpause`, `stop`, `start`: `ProcessBuilder` con `docker compose -f deploy/docker-compose.yml <cmd> <svc>`; falla si el exit code ≠ 0. Excepción: `unpause` y `start` usados en `@AfterEach` son no-op si el contenedor ya está corriendo (consultar antes con `docker compose ps --format json`).
- `Aws`: SDK v2 con endpoint `http://localhost:4566`, región `us-east-1`, credenciales `test/test`.
- `Prometheus.alertFiring(name)`: GET `http://localhost:9090/api/v1/alerts`.
- `Jaeger.tracesByTag(service, "order.id", id)`: GET `http://localhost:16686/api/traces?service=…&tags=…`.
- `@BeforeAll` de la suite: asegura stock de SKU-0001…SKU-0020 en 1000 y capacidad de hoy a hoy+90 (UPSERT vía `Db`), porque las semillas no se re-ejecutan entre corridas.

Patrón:

```java
class F07InventoryDownE2E extends E2EBase {
  @AfterEach void restore() { Compose.unpause("inventory-service"); }

  @Test void sagaCancelaPorTimeoutYNoRetieneRecursos() {
    var before = Snapshot.of("ACME", List.of("SKU-0004"));        // stock + used
    Compose.pause("inventory-service");
    var orderId = Api.createOrder(Tokens.buyer("acme"), order("SKU-0004", 2)).orderId();
    await().pollDelay(25, SECONDS).until(() -> true);
    Compose.unpause("inventory-service");
    await().atMost(90, SECONDS).untilAsserted(() -> {
      var o = Api.getOrder(Tokens.buyer("acme"), orderId);
      assertThat(o.status()).isEqualTo("CANCELLED");
      assertThat(o.reason()).isEqualTo("STEP_TIMEOUT");
    });
    await().atMost(30, SECONDS).untilAsserted(() ->
        assertThat(Snapshot.of("ACME", List.of("SKU-0004"))).isEqualTo(before));
  }
}
```

Usa SKUs distintos en cada test para no mezclar deltas.
