package com.orderflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.e2e.support.Api;
import com.orderflow.e2e.support.E2EBase;
import com.orderflow.e2e.support.Tokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * F17 — Rate limit por empresa. 100 GET {@code /api/orders} concurrentes como buyer-acme y, a la
 * vez, 5 como buyer-globex. Esperado: ≥ 1 respuesta 429 para ACME; las 5 de GLOBEX → 200.
 */
class F17RateLimitPerCompanyE2E extends E2EBase {

  private static final int ACME_REQUESTS = 100;
  private static final int GLOBEX_REQUESTS = 5;
  /** Burst 40 a 20/s (SPEC §12): en 2 s el bucket de ACME vuelve a estar lleno. */
  private static final Duration REFILL = Duration.ofSeconds(2);

  /** Restaura el bucket que la ráfaga agotó: el test siguiente de la suite no debe ver 429. */
  @AfterEach
  void waitForAcmeBucket() {
    String acme = Tokens.buyer("acme");
    await()
        .pollDelay(REFILL)
        .atMost(REFILL.plusSeconds(10))
        .until(() -> Api.listOrders(acme).statusCode() == 200);
  }

  @Test
  void elLimiteDeUnaEmpresaNoAfectaAOtra() throws Exception {
    // Tokens antes de disparar: el password grant no debe entrar en la ráfaga.
    String acme = Tokens.buyer("acme");
    String globex = Tokens.buyer("globex");

    var start = new CountDownLatch(1);
    var acmeCalls = new ArrayList<CompletableFuture<Integer>>();
    var globexCalls = new ArrayList<CompletableFuture<Integer>>();

    try (var pool = Executors.newFixedThreadPool(ACME_REQUESTS + GLOBEX_REQUESTS);
        var http = HttpClient.newHttpClient()) {
      for (int i = 0; i < ACME_REQUESTS; i++) {
        acmeCalls.add(listOrders(http, pool, start, acme));
      }
      for (int i = 0; i < GLOBEX_REQUESTS; i++) {
        globexCalls.add(listOrders(http, pool, start, globex));
      }
      start.countDown();

      List<Integer> acmeStatuses = join(acmeCalls);
      List<Integer> globexStatuses = join(globexCalls);

      assertThat(acmeStatuses).hasSize(ACME_REQUESTS).contains(429);
      assertThat(globexStatuses).hasSize(GLOBEX_REQUESTS).containsOnly(200);
    }
  }

  private static CompletableFuture<Integer> listOrders(
      HttpClient http, Executor pool, CountDownLatch start, String token) {
    var request =
        HttpRequest.newBuilder(URI.create(Api.BASE_URL + "/api/orders"))
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            start.await();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        },
        pool);
  }

  private static List<Integer> join(List<CompletableFuture<Integer>> calls) throws Exception {
    var statuses = new ArrayList<Integer>();
    for (CompletableFuture<Integer> call : calls) {
      statuses.add(call.get(30, TimeUnit.SECONDS));
    }
    return statuses;
  }
}
