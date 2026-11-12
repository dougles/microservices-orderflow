package com.orderflow.order.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderRulesTest {

  /** 2026-11-07 02:00 UTC = 2026-11-06 22:00 en La Paz (UTC-4): "hoy" es el 6. */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-07T02:00:00Z"), ZoneOffset.UTC);

  private static final LocalDate TODAY_LA_PAZ = LocalDate.parse("2026-11-06");

  @Test
  void aceptaDesdeMananaHastaHoyMas60EnLaZonaDeLaPaz() {
    assertThatCode(() -> OrderRules.check(order(TODAY_LA_PAZ.plusDays(1)), CLOCK))
        .doesNotThrowAnyException();
    assertThatCode(() -> OrderRules.check(order(TODAY_LA_PAZ.plusDays(60)), CLOCK))
        .doesNotThrowAnyException();
  }

  @Test
  void rechazaHoyYMasAllaDe60Dias() {
    assertThatThrownBy(() -> OrderRules.check(order(TODAY_LA_PAZ), CLOCK))
        .isInstanceOf(InvalidOrderException.class);
    assertThatThrownBy(() -> OrderRules.check(order(TODAY_LA_PAZ.plusDays(61)), CLOCK))
        .isInstanceOf(InvalidOrderException.class);
  }

  @Test
  void laFechaUtcNoEsLaReferencia() {
    // En UTC ya es el 7: con esa referencia el 7 sería "hoy" y se rechazaría.
    assertThatCode(() -> OrderRules.check(order(LocalDate.parse("2026-11-07")), CLOCK))
        .doesNotThrowAnyException();
  }

  @Test
  void rechazaSkuRepetido() {
    var order =
        new NewOrder(
            TODAY_LA_PAZ.plusDays(5),
            List.of(new NewOrder.Item("SKU-0001", 1), new NewOrder.Item("SKU-0001", 2)));
    assertThatThrownBy(() -> OrderRules.check(order, CLOCK))
        .isInstanceOf(InvalidOrderException.class);
  }

  private static NewOrder order(LocalDate deliveryDate) {
    return new NewOrder(deliveryDate, List.of(new NewOrder.Item("SKU-0001", 1)));
  }
}
