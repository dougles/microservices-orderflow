package com.orderflow.e2e;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.e2e.support.Api;
import com.orderflow.e2e.support.Compose;
import com.orderflow.e2e.support.Db;
import com.orderflow.e2e.support.E2EBase;
import com.orderflow.e2e.support.Tokens;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * F11 — {@code pause kafka}; POST; ≥ 1 fila sin publicar en el outbox de {@code orders}; a los 8 s
 * {@code unpause}: POST → 202 con Kafka caído; luego CONFIRMED.
 */
class F11KafkaDownE2E extends E2EBase {

  private static final String KAFKA = "kafka";

  @AfterEach
  void restore() {
    Compose.unpause(KAFKA);
  }

  @Test
  void elPedidoSeAceptaConKafkaCaidoYLuegoSeConfirma() {
    String token = Tokens.buyer("acme");

    Compose.pause(KAFKA);
    var orderId = Api.createOrder(token, Api.order("SKU-0008", 1)).orderId();
    assertThat(
            Db.count(
                Db.Database.ORDERS,
                "SELECT count(*) FROM outbox_message WHERE published_at IS NULL"))
        .isGreaterThanOrEqualTo(1);
    await().pollDelay(8, SECONDS).atMost(9, SECONDS).until(() -> true);
    Compose.unpause(KAFKA);

    await()
        .atMost(Duration.ofSeconds(90))
        .untilAsserted(
            () -> assertThat(Api.getOrder(token, orderId).status()).isEqualTo("CONFIRMED"));
  }
}
