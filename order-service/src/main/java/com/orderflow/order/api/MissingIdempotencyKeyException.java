package com.orderflow.order.api;

class MissingIdempotencyKeyException extends RuntimeException {

  MissingIdempotencyKeyException() {
    super("Idempotency-Key ausente o no es UUID");
  }
}
