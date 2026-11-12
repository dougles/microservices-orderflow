package com.orderflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.e2e.support.Api;
import com.orderflow.e2e.support.Aws;
import com.orderflow.e2e.support.Db;
import com.orderflow.e2e.support.E2EBase;
import com.orderflow.e2e.support.Snapshot;
import com.orderflow.e2e.support.Tokens;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F00 — buyer-acme: SKU-0001 ×2 y SKU-0002 ×3, entrega hoy+7: CONFIRMED; stock −2 y −3; {@code
 * used} de ACME + total; {@code booked} de la fecha +1; existe {@code
 * s3://orderflow-invoices/invoices/ACME/{orderId}.pdf} con content-type {@code application/pdf}.
 */
class F00HappyPathE2E extends E2EBase {

  private static final List<String> SKUS = List.of("SKU-0001", "SKU-0002");

  @Test
  void elPedidoSeConfirmaYSeFactura() {
    String token = Tokens.buyer("acme");
    LocalDate date = Api.defaultDeliveryDate();
    var before = Snapshot.of("ACME", SKUS);
    long bookedBefore = booked(date);

    var orderId =
        Api.createOrder(token, Api.order(date, "SKU-0001", 2, "SKU-0002", 3)).orderId();

    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> assertThat(Api.getOrder(token, orderId).status()).isEqualTo("CONFIRMED"));
    var total = new BigDecimal(Api.getOrder(token, orderId).total());
    var after = Snapshot.of("ACME", SKUS);
    assertThat(after.stock().get("SKU-0001")).isEqualTo(before.stock().get("SKU-0001") - 2);
    assertThat(after.stock().get("SKU-0002")).isEqualTo(before.stock().get("SKU-0002") - 3);
    assertThat(after.used()).isEqualByComparingTo(before.used().add(total));
    assertThat(booked(date)).isEqualTo(bookedBefore + 1);

    String key = "invoices/ACME/" + orderId + ".pdf";
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> {
              var invoice = Aws.head("orderflow-invoices", key);
              assertThat(invoice).as(key).isNotNull();
              assertThat(invoice.contentType()).isEqualTo("application/pdf");
            });
  }

  private static long booked(LocalDate date) {
    return Db.count(
        Db.Database.SHIPPING,
        "SELECT booked FROM shipping_capacity WHERE delivery_date = ?",
        Date.valueOf(date));
  }
}
