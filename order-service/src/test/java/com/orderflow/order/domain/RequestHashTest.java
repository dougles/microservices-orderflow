package com.orderflow.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestHashTest {

  private static final LocalDate DATE = LocalDate.parse("2026-11-20");

  @Test
  void elOrdenDeLosItemsNoCambiaElHash() {
    var a = new NewOrder(DATE, List.of(item("SKU-0001", 5), item("SKU-0002", 3)));
    var b = new NewOrder(DATE, List.of(item("SKU-0002", 3), item("SKU-0001", 5)));
    assertThat(RequestHash.of(a)).isEqualTo(RequestHash.of(b)).hasSize(64);
  }

  @Test
  void otraCantidadOtraFechaUOtroSkuCambianElHash() {
    var base = RequestHash.of(new NewOrder(DATE, List.of(item("SKU-0001", 5))));
    assertThat(RequestHash.of(new NewOrder(DATE, List.of(item("SKU-0001", 6))))).isNotEqualTo(base);
    assertThat(RequestHash.of(new NewOrder(DATE.plusDays(1), List.of(item("SKU-0001", 5)))))
        .isNotEqualTo(base);
    assertThat(RequestHash.of(new NewOrder(DATE, List.of(item("SKU-0002", 5))))).isNotEqualTo(base);
  }

  private static NewOrder.Item item(String sku, int quantity) {
    return new NewOrder.Item(sku, quantity);
  }
}
