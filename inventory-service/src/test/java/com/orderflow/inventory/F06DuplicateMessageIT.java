package com.orderflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.contracts.ReserveStock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * F06 — Mensaje duplicado. Publicar 2 veces el mismo ReserveStock (mismo {@code message_id}).
 *
 * <p>Esperado: stock descontado 1 vez; exactamente 1 StockReserved en {@code inventory.replies}.
 */
class F06DuplicateMessageIT extends AbstractIT {

  private static final Duration LIMIT = Duration.ofSeconds(15);

  @Test
  void elDuplicadoNoTieneEfectoNiRespuesta() throws Exception {
    stock("F06-A", 10);
    UUID orderId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    var command = new ReserveStock(orderId, List.of(new ReserveStock.Item("F06-A", 3)));

    publishCommand(messageId, orderId, command);
    publishCommand(messageId, orderId, command);

    await().atMost(LIMIT).untilAsserted(() -> assertThat(replies(orderId)).isNotEmpty());
    // Ambos consumidos y sin nada por publicar: el conteo exacto ya no puede cambiar.
    awaitCommandsConsumedAndPublished(LIMIT);

    assertThat(replies(orderId)).extracting(Received::type).containsExactly("StockReserved");
    assertThat(available("F06-A")).isEqualTo(7);
  }
}
