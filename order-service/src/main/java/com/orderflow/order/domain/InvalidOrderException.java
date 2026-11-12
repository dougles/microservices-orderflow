package com.orderflow.order.domain;

/** Pedido que no cumple las reglas de validación de SPEC §5.2 (400 VALIDATION_ERROR). */
public class InvalidOrderException extends RuntimeException {

  public InvalidOrderException(String message) {
    super(message);
  }
}
