package com.orderflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.e2e.support.Api;
import com.orderflow.e2e.support.Db;
import com.orderflow.e2e.support.E2EBase;
import com.orderflow.e2e.support.Snapshot;
import com.orderflow.e2e.support.Tokens;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * F05 — Vía JDBC: {@code max_orders = booked} para la fecha D; pedido con entrega D: CANCELLED,
 * SHIPPING_NO_CAPACITY; stock y crédito sin cambios.
 */
class F05NoShippingCapacityE2E extends E2EBase {

  private static final List<String> SKUS = List.of("SKU-0006");
  private final LocalDate date = LocalDate.now(Api.ZONE).plusDays(11);

  @AfterEach
  void restoreCapacity() {
    Db.update(
        Db.Database.SHIPPING,
        "UPDATE shipping_capacity SET max_orders = 100 WHERE delivery_date = ?",
        Date.valueOf(date));
  }

  @Test
  void sinCupoCancelaYDevuelveStockYCredito() {
    String token = Tokens.buyer("acme");
    var before = Snapshot.of("ACME", SKUS);
    Db.update(
        Db.Database.SHIPPING,
        "UPDATE shipping_capacity SET max_orders = booked WHERE delivery_date = ?",
        Date.valueOf(date));

    var orderId = Api.createOrder(token, Api.order(date, "SKU-0006", 1)).orderId();

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              var order = Api.getOrder(token, orderId);
              assertThat(order.status()).isEqualTo("CANCELLED");
              assertThat(order.reason()).isEqualTo("SHIPPING_NO_CAPACITY");
            });
    assertThat(Snapshot.of("ACME", SKUS)).isEqualTo(before);
  }
}
