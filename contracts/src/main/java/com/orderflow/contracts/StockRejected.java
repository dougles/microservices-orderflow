package com.orderflow.contracts;

import java.util.UUID;

public record StockRejected(UUID orderId, Reason reason) {

  public enum Reason {
    OUT_OF_STOCK,
    ALREADY_RELEASED
  }
}
