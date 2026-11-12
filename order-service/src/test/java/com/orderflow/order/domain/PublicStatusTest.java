package com.orderflow.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tabla "Estado público" de SPEC §5.2. */
class PublicStatusTest {

  @ParameterizedTest
  @CsvSource(
      nullValues = "null",
      value = {
        "AWAITING_STOCK,      null,                  PENDING,   null",
        "AWAITING_CREDIT,     null,                  PENDING,   null",
        "AWAITING_SHIPMENT,   null,                  PENDING,   null",
        "COMPENSATING,        SHIPPING_NO_CAPACITY,  PENDING,   null",
        "CONFIRMED,           null,                  CONFIRMED, null",
        "REJECTED,            OUT_OF_STOCK,          REJECTED,  OUT_OF_STOCK",
        "CANCELLED,           CREDIT_LIMIT_EXCEEDED, REJECTED,  CREDIT_LIMIT_EXCEEDED",
        "REJECTED,            NO_CREDIT_LINE,        REJECTED,  NO_CREDIT_LINE",
        "CANCELLED,           SHIPPING_NO_CAPACITY,  CANCELLED, SHIPPING_NO_CAPACITY",
        "CANCELLED,           STEP_TIMEOUT,          CANCELLED, STEP_TIMEOUT",
        "COMPENSATION_FAILED, SHIPPING_NO_CAPACITY,  CANCELLED, SHIPPING_NO_CAPACITY",
        "COMPENSATION_FAILED, CREDIT_LIMIT_EXCEEDED, REJECTED,  CREDIT_LIMIT_EXCEEDED"
      })
  void derivaElEstadoPublico(
      SagaState state, FailureReason reason, PublicStatus expected, FailureReason exposed) {
    PublicStatus status = PublicStatus.of(state, reason);
    assertThat(status).isEqualTo(expected);
    assertThat(status.exposedReason(reason)).isEqualTo(exposed);
  }

  @ParameterizedTest
  @CsvSource({"REJECTED", "CANCELLED", "COMPENSATION_FAILED"})
  void unEstadoFinalFallidoSinRazonEsUnError(SagaState state) {
    assertThatThrownBy(() -> PublicStatus.of(state, null)).isInstanceOf(IllegalStateException.class);
  }
}
