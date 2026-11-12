package com.orderflow.order.domain;

import java.util.UUID;

/** Pedido inexistente o de otra empresa: ambos responden 404 (SPEC §4). */
public class OrderNotFoundException extends RuntimeException {

  public OrderNotFoundException(UUID orderId) {
    super("pedido no encontrado: " + orderId);
  }
}
