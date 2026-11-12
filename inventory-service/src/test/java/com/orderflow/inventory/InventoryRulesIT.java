package com.orderflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.contracts.ReleaseStock;
import com.orderflow.contracts.ReserveStock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reglas de SPEC §9 (inventory), de punta a punta por Kafka. */
class InventoryRulesIT extends AbstractIT {

  private static final Duration LIMIT = Duration.ofSeconds(15);

  private static ReserveStock reserve(UUID orderId, Object... skuQuantity) {
    var items = new java.util.ArrayList<ReserveStock.Item>();
    for (int i = 0; i < skuQuantity.length; i += 2) {
      items.add(new ReserveStock.Item((String) skuQuantity[i], (Integer) skuQuantity[i + 1]));
    }
    return new ReserveStock(orderId, items);
  }

  private void awaitReplyTypes(UUID orderId, String... types) {
    await()
        .atMost(LIMIT)
        .untilAsserted(
            () ->
                assertThat(replies(orderId).stream().map(Received::type).toList())
                    .containsExactly(types));
  }

  private String reasonOfLast(UUID orderId) {
    return JSON.readTree(replies(orderId).getLast().value()).get("reason").asString();
  }

  @Test
  void reservaDescuentaTodosLosItems() throws Exception {
    stock("A-1", 10);
    stock("B-1", 10);
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, reserve(orderId, "A-1", 2, "B-1", 3));

    awaitReplyTypes(orderId, "StockReserved");
    assertThat(available("A-1")).isEqualTo(8);
    assertThat(available("B-1")).isEqualTo(7);
  }

  @Test
  void siUnItemNoAlcanzaNoSeDescuentaNinguno() throws Exception {
    stock("A-2", 10);
    stock("B-2", 1);
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, reserve(orderId, "A-2", 2, "B-2", 3));

    awaitReplyTypes(orderId, "StockRejected");
    assertThat(reasonOfLast(orderId)).isEqualTo("OUT_OF_STOCK");
    assertThat(available("A-2")).isEqualTo(10);
    assertThat(available("B-2")).isEqualTo(1);
  }

  @Test
  void skuSinFilaEsOutOfStockYNoDejaReserva() throws Exception {
    stock("A-3", 10);
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, reserve(orderId, "A-3", 2, "NO-EXISTE", 1));

    awaitReplyTypes(orderId, "StockRejected");
    assertThat(reasonOfLast(orderId)).isEqualTo("OUT_OF_STOCK");
    assertThat(available("A-3")).isEqualTo(10);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation", Integer.class)).isZero();
  }

  @Test
  void reservaRepetidaConOtroMessageIdRespondeSinDescontarDeNuevo() throws Exception {
    stock("A-4", 10);
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, reserve(orderId, "A-4", 2));
    publishCommand(orderId, reserve(orderId, "A-4", 2));

    awaitReplyTypes(orderId, "StockReserved", "StockReserved");
    assertThat(available("A-4")).isEqualTo(8);
  }

  @Test
  void liberarDevuelveElStockYEsIdempotente() throws Exception {
    stock("A-5", 10);
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, reserve(orderId, "A-5", 4));
    awaitReplyTypes(orderId, "StockReserved");
    publishCommand(orderId, new ReleaseStock(orderId));
    publishCommand(orderId, new ReleaseStock(orderId));

    awaitReplyTypes(orderId, "StockReserved", "StockReleased", "StockReleased");
    assertThat(available("A-5")).isEqualTo(10);
  }

  @Test
  void reservarTrasLiberarSeRechaza() throws Exception {
    stock("A-6", 10);
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, reserve(orderId, "A-6", 4));
    awaitReplyTypes(orderId, "StockReserved");
    publishCommand(orderId, new ReleaseStock(orderId));
    awaitReplyTypes(orderId, "StockReserved", "StockReleased");

    publishCommand(orderId, reserve(orderId, "A-6", 4));

    awaitReplyTypes(orderId, "StockReserved", "StockReleased", "StockRejected");
    assertThat(reasonOfLast(orderId)).isEqualTo("ALREADY_RELEASED");
    assertThat(available("A-6")).isEqualTo(10);
  }

  @Test
  void liberarSinReservaDejaLapidaYLaReservaTardiaSeRechaza() throws Exception {
    stock("A-7", 10);
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, new ReleaseStock(orderId));
    awaitReplyTypes(orderId, "StockReleased");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM reservation WHERE order_id = ?", String.class, orderId))
        .isEqualTo("RELEASED");

    publishCommand(orderId, reserve(orderId, "A-7", 4));

    awaitReplyTypes(orderId, "StockReleased", "StockRejected");
    assertThat(reasonOfLast(orderId)).isEqualTo("ALREADY_RELEASED");
    assertThat(available("A-7")).isEqualTo(10);
    assertThat(replies(orderId)).extracting(Received::key).isEqualTo(
        List.of(orderId.toString(), orderId.toString()));
  }
}
