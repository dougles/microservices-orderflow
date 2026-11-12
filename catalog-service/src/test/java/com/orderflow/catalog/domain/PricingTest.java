package com.orderflow.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PricingTest {

  @Test
  void aplicaElDescuento() {
    assertThat(Pricing.unitPrice(new BigDecimal("10.00"), new BigDecimal("10")))
        .isEqualTo(new BigDecimal("9.00"));
  }

  @Test
  void sinDescuentoDevuelveElPrecioBaseConEscala2() {
    assertThat(Pricing.unitPrice(new BigDecimal("10"), BigDecimal.ZERO))
        .isEqualTo(new BigDecimal("10.00"));
  }

  @Test
  void redondeaHalfEven() {
    assertThat(Pricing.unitPrice(new BigDecimal("1.25"), new BigDecimal("50")))
        .isEqualTo(new BigDecimal("0.62"));
    assertThat(Pricing.unitPrice(new BigDecimal("1.35"), new BigDecimal("50")))
        .isEqualTo(new BigDecimal("0.68"));
  }
}
