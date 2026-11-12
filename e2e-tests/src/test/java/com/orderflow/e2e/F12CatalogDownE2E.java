package com.orderflow.e2e;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.e2e.support.Api;
import com.orderflow.e2e.support.Compose;
import com.orderflow.e2e.support.E2EBase;
import com.orderflow.e2e.support.Tokens;
import io.restassured.response.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * F12 — Catálogo caído / circuit breaker. {@code pause} de las 2 réplicas de catalog-service; 10
 * POST secuenciales; luego 20 POST; {@code unpause}; repetir un POST con una key ya usada.
 *
 * <p>Esperado: primeros 10 → 503 CATALOG_UNAVAILABLE, cada uno ≤ 3 s. Siguientes 20 → 503 en &lt;
 * 100 ms cada uno. Tras unpause y 30 s, la key reutilizada → 202 (no se había guardado).
 */
class F12CatalogDownE2E extends E2EBase {

  private static final String CATALOG = "catalog-service";

  @AfterEach
  void restore() {
    Compose.unpause(CATALOG);
  }

  @Test
  void elCircuitoSeAbreYLaKeyNoSeGuarda() {
    String token = Tokens.buyer("acme");
    String body = Api.order("SKU-0005", 1);
    var usedKeys = new ArrayList<UUID>();

    Compose.pause(CATALOG);

    for (int i = 0; i < 10; i++) {
      UUID key = UUID.randomUUID();
      usedKeys.add(key);
      Timed call = timed(() -> Api.postOrder(token, body, key));
      assertCatalogUnavailable(call.response());
      assertThat(call.elapsed())
          .as("POST %d con catalog pausado", i + 1)
          .isLessThanOrEqualTo(Duration.ofSeconds(3));
    }

    for (int i = 0; i < 20; i++) {
      Timed call = timed(() -> Api.postOrder(token, body, UUID.randomUUID()));
      assertCatalogUnavailable(call.response());
      assertThat(call.elapsed())
          .as("POST %d con el circuito abierto", i + 1)
          .isLessThan(Duration.ofMillis(100));
    }

    Compose.unpause(CATALOG);
    await().pollDelay(30, SECONDS).atMost(31, SECONDS).until(() -> true);

    Api.postOrder(token, body, usedKeys.getFirst()).then().statusCode(202);
  }

  private static void assertCatalogUnavailable(Response response) {
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.jsonPath().getString("code")).isEqualTo("CATALOG_UNAVAILABLE");
  }

  private record Timed(Response response, Duration elapsed) {}

  private static Timed timed(Supplier<Response> call) {
    long start = System.nanoTime();
    Response response = call.get();
    return new Timed(response, Duration.ofNanos(System.nanoTime() - start));
  }
}
