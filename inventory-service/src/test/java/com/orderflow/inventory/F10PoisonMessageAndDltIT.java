package com.orderflow.inventory;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderflow.contracts.ReserveStock;
import com.orderflow.contracts.Topics;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * F10 — Poison message y DLT.
 *
 * <p>(a) JSON inválido con headers válidos, seguido de un ReserveStock válido: va al DLT tras 1
 * intento y el siguiente mensaje se procesa. (b) {@code pause} de Postgres 20 s mientras llega un
 * ReserveStock válido; luego {@code unpause} y replay con rol ops: va al DLT tras 4 intentos y tras
 * el replay se procesa, con el stock descontado y 1 StockReserved.
 */
class F10PoisonMessageAndDltIT extends AbstractIT {

  private static final Duration LIMIT = Duration.ofSeconds(60);

  /** 3 reintentos con backoff de 1, 2 y 4 s (SPEC §7.3). */
  private static final long FOUR_ATTEMPTS_MIN_MILLIS = 7_000;

  private static ReserveStock reserve(UUID orderId, String sku, int quantity) {
    return new ReserveStock(orderId, List.of(new ReserveStock.Item(sku, quantity)));
  }

  @Test
  void jsonInvalidoVaAlDltSinReintentosYNoFrenaAlSiguiente() throws Exception {
    stock("F10-A", 10);
    UUID poisonId = UUID.randomUUID();
    UUID validId = UUID.randomUUID();

    publishRaw(UUID.randomUUID(), poisonId, "ReserveStock", "{esto no es json");
    publishCommand(validId, reserve(validId, "F10-A", 2));

    await()
        .atMost(LIMIT)
        .untilAsserted(
            () -> {
              assertThat(readAll(Topics.INVENTORY_COMMANDS_DLT, poisonId)).hasSize(1);
              assertThat(replies(validId))
                  .extracting(Received::type)
                  .containsExactly("StockReserved");
            });

    Received dead = readAll(Topics.INVENTORY_COMMANDS_DLT, poisonId).getFirst();
    assertThat(dead.millisInOriginalTopic())
        .as("un solo intento: sin backoff antes del DLT")
        .isLessThan(FOUR_ATTEMPTS_MIN_MILLIS);
    assertThat(available("F10-A")).isEqualTo(8);
  }

  @Test
  void postgresCaidoVaAlDltTrasCuatroIntentosYElReplayLoProcesa() throws Exception {
    stock("F10-B", 10);
    UUID orderId = UUID.randomUUID();
    var docker = postgres.getDockerClient();

    docker.pauseContainerCmd(postgres.getContainerId()).exec();
    try {
      publishCommand(orderId, reserve(orderId, "F10-B", 4));
      await().pollDelay(20, SECONDS).atMost(21, SECONDS).until(() -> true);
    } finally {
      docker.unpauseContainerCmd(postgres.getContainerId()).exec();
    }

    await()
        .atMost(LIMIT)
        .untilAsserted(
            () -> assertThat(readAll(Topics.INVENTORY_COMMANDS_DLT, orderId)).hasSize(1));
    Received dead = readAll(Topics.INVENTORY_COMMANDS_DLT, orderId).getFirst();
    assertThat(dead.millisInOriginalTopic())
        .as("4 intentos: los 3 backoffs transcurren antes del DLT")
        .isGreaterThanOrEqualTo(FOUR_ATTEMPTS_MIN_MILLIS);
    assertThat(available("F10-B")).isEqualTo(10);

    String response =
        mvc.perform(
                post("/ops/dlt/{topic}/replay", Topics.INVENTORY_COMMANDS)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ops"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JSON.readTree(response).get("replayed").asInt()).isGreaterThanOrEqualTo(1);

    await()
        .atMost(LIMIT)
        .untilAsserted(
            () ->
                assertThat(replies(orderId))
                    .extracting(Received::type)
                    .containsExactly("StockReserved"));
    assertThat(available("F10-B")).isEqualTo(6);
  }
}
