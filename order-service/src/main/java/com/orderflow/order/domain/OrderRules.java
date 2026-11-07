package com.orderflow.order.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;

/** Reglas de SPEC §5.2 que Bean Validation no expresa: sku no repetido y rango de entrega. */
public final class OrderRules {

  static final ZoneId ZONE = ZoneId.of("America/La_Paz");
  static final int MAX_DAYS_AHEAD = 60;

  private OrderRules() {}

  public static void check(NewOrder order, Clock clock) {
    var seen = new HashSet<String>();
    for (NewOrder.Item item : order.items()) {
      if (!seen.add(item.sku())) {
        throw new InvalidOrderException("sku repetido: " + item.sku());
      }
    }
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    LocalDate delivery = order.deliveryDate();
    if (delivery.isBefore(today.plusDays(1)) || delivery.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
      throw new InvalidOrderException("deliveryDate fuera de rango: " + delivery);
    }
  }
}
