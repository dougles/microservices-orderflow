package com.orderflow.order.domain;

public class IdempotencyKeyReusedException extends RuntimeException {

  public IdempotencyKeyReusedException() {
    super("Idempotency-Key usada con otro body");
  }
}
