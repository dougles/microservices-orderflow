package com.orderflow.contracts;

import java.util.List;
import java.util.UUID;

public record ReserveStock(UUID orderId, List<Item> items) {

  public record Item(String sku, int quantity) {}
}
