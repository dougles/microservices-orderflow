package com.orderflow.contracts;

import java.util.UUID;

public record CreditRejected(UUID orderId, Reason reason) {

  public enum Reason {
    CREDIT_LIMIT_EXCEEDED,
    NO_CREDIT_LINE,
    ALREADY_RELEASED
  }
}
