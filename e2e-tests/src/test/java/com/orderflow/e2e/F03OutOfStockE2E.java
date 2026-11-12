package com.orderflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.e2e.support.Api;
import com.orderflow.e2e.support.E2EBase;
import com.orderflow.e2e.support.Snapshot;
import com.orderflow.e2e.support.Tokens;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** F03 — SKU-0003 ×5000 (stock 1000): REJECTED, OUT_OF_STOCK; stock y crédito sin cambios. */
class F03OutOfStockE2E extends E2EBase {

  private static final List<String> SKUS = List.of("SKU-0003");

  @Test
  void pedidoSinStockSeRechazaSinTocarNada() {
    String token = Tokens.buyer("acme");
    var before = Snapshot.of("ACME", SKUS);

    var orderId = Api.createOrder(token, Api.order("SKU-0003", 5000)).orderId();

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              var order = Api.getOrder(token, orderId);
              assertThat(order.status()).isEqualTo("REJECTED");
              assertThat(order.reason()).isEqualTo("OUT_OF_STOCK");
            });
    assertThat(Snapshot.of("ACME", SKUS)).isEqualTo(before);
  }
}
