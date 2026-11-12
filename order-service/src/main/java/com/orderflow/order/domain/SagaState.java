package com.orderflow.order.domain;

/** Estados de la saga (SPEC §8.1). */
public enum SagaState {
  AWAITING_STOCK,
  AWAITING_CREDIT,
  AWAITING_SHIPMENT,
  COMPENSATING,
  CONFIRMED,
  REJECTED,
  CANCELLED,
  COMPENSATION_FAILED;

  public boolean isAwaiting() {
    return this == AWAITING_STOCK || this == AWAITING_CREDIT || this == AWAITING_SHIPMENT;
  }

  public boolean isFinal() {
    return !isAwaiting() && this != COMPENSATING;
  }
}
