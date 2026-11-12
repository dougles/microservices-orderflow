package com.orderflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.e2e.support.Api;
import com.orderflow.e2e.support.E2EBase;
import com.orderflow.e2e.support.Jaeger;
import com.orderflow.e2e.support.Tokens;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * F18 — Pedido como en F00 hasta CONFIRMED: en la API de Jaeger existe una traza con atributo
 * {@code order.id} = orderId que contiene spans de gateway, order-service, inventory-service,
 * credit-service y shipping-service.
 */
class F18DistributedTraceE2E extends E2EBase {

  private static final String[] SERVICES = {
    "gateway", "order-service", "inventory-service", "credit-service", "shipping-service"
  };

  @Test
  void laSagaCompletaEsUnaSolaTraza() {
    String token = Tokens.buyer("acme");
    var orderId = Api.createOrder(token, Api.order("SKU-0001", 2, "SKU-0002", 3)).orderId();
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> assertThat(Api.getOrder(token, orderId).status()).isEqualTo("CONFIRMED"));

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThat(Jaeger.tracesByTag("order-service", "order.id", orderId.toString()))
                    .anySatisfy(trace -> assertThat(trace.services()).contains(SERVICES)));
  }
}
