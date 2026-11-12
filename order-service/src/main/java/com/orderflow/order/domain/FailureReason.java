package com.orderflow.order.domain;

/** Valores de {@code failure_reason} (SPEC §8.1). */
public enum FailureReason {
  OUT_OF_STOCK,
  CREDIT_LIMIT_EXCEEDED,
  NO_CREDIT_LINE,
  SHIPPING_NO_CAPACITY,
  STEP_TIMEOUT
}
