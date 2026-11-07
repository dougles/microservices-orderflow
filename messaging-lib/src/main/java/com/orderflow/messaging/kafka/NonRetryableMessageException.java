package com.orderflow.messaging.kafka;

/** El mensaje nunca podrá procesarse: va directo al DLT tras el primer intento (SPEC §7.3). */
public class NonRetryableMessageException extends RuntimeException {

  public NonRetryableMessageException(String message) {
    super(message);
  }
}
