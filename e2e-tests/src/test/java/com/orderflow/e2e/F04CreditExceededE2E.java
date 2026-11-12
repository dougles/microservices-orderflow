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

/**
 * F04 — buyer-globex: SKU-0020 ×3 (total 600.00 &gt; límite 500.00): REJECTED,
 * CREDIT_LIMIT_EXCEEDED; stock y crédito sin cambios.
 */
class F04CreditExceededE2E extends E2EBase {

  private static final List<String> SKUS = List.of("SKU-0020");

  @Test
  void creditoExcedidoSeRechazaYDevuelveElStock() {
    String token = Tokens.buyer("globex");
    var before = Snapshot.of("GLOBEX", SKUS);

    var orderId = Api.createOrder(token, Api.order("SKU-0020", 3)).orderId();

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              var order = Api.getOrder(token, orderId);
              assertThat(order.status()).isEqualTo("REJECTED");
              assertThat(order.reason()).isEqualTo("CREDIT_LIMIT_EXCEEDED");
            });
    assertThat(Snapshot.of("GLOBEX", SKUS)).isEqualTo(before);
  }
}
