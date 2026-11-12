package com.orderflow.order;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * F01 — Reintento con la misma Idempotency-Key. Mismo body y misma key: 2 POST secuenciales y luego
 * 2 concurrentes. Las 4 respuestas son 202 con el mismo orderId; existe 1 solo pedido.
 */
class F01IdempotentRetryIT extends AbstractIT {

  @Test
  void dosSecuencialesYDosConcurrentesDevuelvenElMismoPedido() throws Exception {
    stubPrices("SKU-0001", "9.00");
    UUID key = UUID.randomUUID();
    String body = order(TODAY.plusDays(7), "SKU-0001", 2);

    var orderIds = new ArrayList<String>();
    orderIds.add(accepted(key, body));
    orderIds.add(accepted(key, body));
    orderIds.addAll(concurrently(() -> accepted(key, body), 2));

    assertThat(orderIds).hasSize(4).containsOnly(orderIds.getFirst());
    assertThat(count("orders")).isEqualTo(1);
  }

  /**
   * Los dos concurrentes llegan antes de que exista el registro: ambos pasan la búsqueda, ambos
   * consultan catalog y compiten por la PK. El segundo espera el conflicto y devuelve la respuesta
   * guardada (SPEC §10).
   */
  @Test
  void dosConcurrentesConUnaKeyNuevaCreanUnSoloPedido() throws Exception {
    wiremock.stubFor(
        WireMock.get(urlPathEqualTo(PRICES))
            .willReturn(
                okJson(
                        """
                        {"currency":"BOB","items":[{"sku":"SKU-0001","unitPrice":"9.00"}],"unknownSkus":[]}
                        """)
                    .withFixedDelay(300)));
    UUID key = UUID.randomUUID();
    String body = order(TODAY.plusDays(7), "SKU-0001", 2);

    List<String> orderIds = concurrently(() -> accepted(key, body), 2);

    assertThat(orderIds).hasSize(2).containsOnly(orderIds.getFirst());
    assertThat(count("orders")).isEqualTo(1);
    assertThat(count("outbox_message")).isEqualTo(1);
    assertThat(count("idempotency_key")).isEqualTo(1);
  }

  private String accepted(UUID key, String body) throws Exception {
    String response =
        createOrder("ACME", key, body)
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(response).get("orderId").asString();
  }

  private static List<String> concurrently(Callable<String> call, int n) throws Exception {
    var start = new CountDownLatch(1);
    var futures = new ArrayList<Future<String>>();
    try (var pool = Executors.newFixedThreadPool(n)) {
      for (int i = 0; i < n; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return call.call();
                }));
      }
      start.countDown();
      var results = new ArrayList<String>();
      for (Future<String> f : futures) {
        results.add(f.get(30, TimeUnit.SECONDS));
      }
      return results;
    }
  }
}
