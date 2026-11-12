package com.orderflow.e2e;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.e2e.support.Api;
import com.orderflow.e2e.support.Compose;
import com.orderflow.e2e.support.E2EBase;
import com.orderflow.e2e.support.Snapshot;
import com.orderflow.e2e.support.Tokens;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * F07 — {@code pause inventory-service}; POST; esperar 25 s; {@code unpause}: CANCELLED,
 * STEP_TIMEOUT; stock y crédito sin cambios.
 */
class F07InventoryDownE2E extends E2EBase {

  private static final String SERVICE = "inventory-service";
  private static final List<String> SKUS = List.of("SKU-0004");

  @AfterEach
  void restore() {
    Compose.unpause(SERVICE);
  }

  @Test
  void laSagaCancelaPorTimeoutYNoRetieneRecursos() {
    String token = Tokens.buyer("acme");
    var before = Snapshot.of("ACME", SKUS);

    Compose.pause(SERVICE);
    var orderId = Api.createOrder(token, Api.order("SKU-0004", 2)).orderId();
    await().pollDelay(25, SECONDS).atMost(26, SECONDS).until(() -> true);
    Compose.unpause(SERVICE);

    await()
        .atMost(Duration.ofSeconds(90))
        .untilAsserted(
            () -> {
              var order = Api.getOrder(token, orderId);
              assertThat(order.status()).isEqualTo("CANCELLED");
              assertThat(order.reason()).isEqualTo("STEP_TIMEOUT");
            });
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(Snapshot.of("ACME", SKUS)).isEqualTo(before));
  }
}
