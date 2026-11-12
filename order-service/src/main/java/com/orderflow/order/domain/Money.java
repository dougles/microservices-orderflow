package com.orderflow.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Dinero: BigDecimal escala 2, HALF_EVEN (SPEC §9). */
public final class Money {

  public static final String CURRENCY = "BOB";

  private Money() {}

  public static BigDecimal of(BigDecimal amount) {
    return amount.setScale(2, RoundingMode.HALF_EVEN);
  }

  /** Total = Σ quantity × unitPrice. */
  public static BigDecimal total(List<OrderLine> lines) {
    return of(
        lines.stream()
            .map(line -> line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  public static String format(BigDecimal amount) {
    return of(amount).toPlainString();
  }
}
