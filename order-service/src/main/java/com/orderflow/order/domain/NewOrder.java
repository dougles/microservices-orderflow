package com.orderflow.order.domain;

import java.time.LocalDate;
import java.util.List;

/** Pedido ya validado en forma (Bean Validation) que llega al dominio. */
public record NewOrder(LocalDate deliveryDate, List<Item> items) {

  public record Item(String sku, int quantity) {}

  public List<String> skus() {
    return items.stream().map(Item::sku).toList();
  }
}
