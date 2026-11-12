package com.orderflow.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.contracts.CancelShipment;
import com.orderflow.contracts.ScheduleShipment;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reglas de SPEC §9 (shipping), de punta a punta por Kafka. */
class ShippingRulesIT extends AbstractIT {

  private static final Duration LIMIT = Duration.ofSeconds(15);

  private static ScheduleShipment schedule(UUID orderId, LocalDate date) {
    return new ScheduleShipment(orderId, "ACME", date);
  }

  private void awaitReplyTypes(UUID orderId, String... types) {
    await()
        .atMost(LIMIT)
        .untilAsserted(
            () ->
                assertThat(replies(orderId).stream().map(Received::type).toList())
                    .containsExactly(types));
  }

  private String field(Received received, String name) {
    return JSON.readTree(received.value()).get(name).asString();
  }

  @Test
  void agendarOcupaUnCupo() throws Exception {
    LocalDate date = LocalDate.of(2030, 1, 1);
    capacity(date, 2, 0);
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, schedule(orderId, date));

    awaitReplyTypes(orderId, "ShipmentScheduled");
    assertThat(field(replies(orderId).getFirst(), "shipmentId")).isNotBlank();
    assertThat(booked(date)).isEqualTo(1);
  }

  @Test
  void sinCupoSeRechaza() throws Exception {
    LocalDate date = LocalDate.of(2030, 1, 2);
    capacity(date, 1, 1);
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, schedule(orderId, date));

    awaitReplyTypes(orderId, "ShipmentRejected");
    assertThat(field(replies(orderId).getFirst(), "reason")).isEqualTo("NO_CAPACITY");
    assertThat(booked(date)).isEqualTo(1);
  }

  @Test
  void fechaSinFilaSeRechaza() throws Exception {
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, schedule(orderId, LocalDate.of(2030, 1, 3)));

    awaitReplyTypes(orderId, "ShipmentRejected");
    assertThat(field(replies(orderId).getFirst(), "reason")).isEqualTo("NO_CAPACITY");
  }

  @Test
  void agendarRepetidoConOtroMessageIdDevuelveElMismoDespacho() throws Exception {
    LocalDate date = LocalDate.of(2030, 1, 4);
    capacity(date, 5, 0);
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, schedule(orderId, date));
    publishCommand(orderId, schedule(orderId, date));

    awaitReplyTypes(orderId, "ShipmentScheduled", "ShipmentScheduled");
    var replies = replies(orderId);
    assertThat(field(replies.get(1), "shipmentId")).isEqualTo(field(replies.get(0), "shipmentId"));
    assertThat(booked(date)).isEqualTo(1);
  }

  @Test
  void cancelarLiberaElCupoYEsIdempotente() throws Exception {
    LocalDate date = LocalDate.of(2030, 1, 5);
    capacity(date, 5, 0);
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, schedule(orderId, date));
    awaitReplyTypes(orderId, "ShipmentScheduled");
    publishCommand(orderId, new CancelShipment(orderId));
    publishCommand(orderId, new CancelShipment(orderId));

    awaitReplyTypes(orderId, "ShipmentScheduled", "ShipmentCancelled", "ShipmentCancelled");
    assertThat(booked(date)).isZero();
  }

  @Test
  void cancelarSinDespachoDejaLapidaYElAgendadoTardioSeRechaza() throws Exception {
    LocalDate date = LocalDate.of(2030, 1, 6);
    capacity(date, 5, 0);
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, new CancelShipment(orderId));
    awaitReplyTypes(orderId, "ShipmentCancelled");

    publishCommand(orderId, schedule(orderId, date));

    awaitReplyTypes(orderId, "ShipmentCancelled", "ShipmentRejected");
    assertThat(field(replies(orderId).getLast(), "reason")).isEqualTo("ALREADY_CANCELLED");
    assertThat(booked(date)).isZero();
  }
}
