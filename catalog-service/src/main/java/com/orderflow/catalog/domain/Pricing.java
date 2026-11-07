package com.orderflow.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** {@code unitPrice = base_price × (1 − discount_pct/100)}, escala 2, HALF_EVEN (SPEC §6). */
public final class Pricing {

  private Pricing() {}

  public static BigDecimal unitPrice(BigDecimal basePrice, BigDecimal discountPct) {
    return basePrice
        .multiply(BigDecimal.ONE.subtract(discountPct.movePointLeft(2)))
        .setScale(2, RoundingMode.HALF_EVEN);
  }
}
