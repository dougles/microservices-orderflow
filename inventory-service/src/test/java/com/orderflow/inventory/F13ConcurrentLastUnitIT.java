package com.orderflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.contracts.ReserveStock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * F13 — Concurrencia sobre la última unidad. Stock de un SKU = 1; 10 ReserveStock concurrentes de
 * pedidos distintos, 1 unidad cada uno.
 *
 * <p>Esperado: exactamente 1 StockReserved y 9 StockRejected(OUT_OF_STOCK); {@code available} = 0,
 * nunca negativo.
 */
class F13ConcurrentLastUnitIT extends AbstractIT {

  private static final Duration LIMIT = Duration.ofSeconds(30);
  private static final String SKU = "F13-A";

  @Test
  void soloUnPedidoSeLlevaLaUltimaUnidad() {
    stock(SKU, 1);
    var orders = new ArrayList<UUID>();
    for (int i = 0; i < 10; i++) {
      orders.add(UUID.randomUUID());
    }

    CompletableFuture.allOf(
            orders.stream()
                .map(id -> CompletableFuture.runAsync(() -> publishReserve(id)))
                .toArray(CompletableFuture[]::new))
        .join();

    await()
        .atMost(LIMIT)
        .untilAsserted(
            () -> {
              assertThat(available(SKU)).isGreaterThanOrEqualTo(0);
              assertThat(allReplies(orders)).hasSize(10);
            });

    List<Received> replies = allReplies(orders);
    assertThat(replies).filteredOn(r -> "StockReserved".equals(r.type())).hasSize(1);
    var rejected = replies.stream().filter(r -> "StockRejected".equals(r.type())).toList();
    assertThat(rejected).hasSize(9);
    assertThat(rejected)
        .allSatisfy(
            r ->
                assertThat(JSON.readTree(r.value()).get("reason").asString())
                    .isEqualTo("OUT_OF_STOCK"));
    assertThat(available(SKU)).isZero();
  }

  private void publishReserve(UUID orderId) {
    try {
      publishCommand(orderId, new ReserveStock(orderId, List.of(new ReserveStock.Item(SKU, 1))));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private List<Received> allReplies(List<UUID> orders) {
    return orders.stream().flatMap(id -> replies(id).stream()).toList();
  }
}
