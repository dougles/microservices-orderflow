package com.orderflow.contracts;

import java.util.UUID;

public record ShipmentRejected(UUID orderId, Reason reason) {

  public enum Reason {
    NO_CAPACITY,
    ALREADY_CANCELLED
  }
}
