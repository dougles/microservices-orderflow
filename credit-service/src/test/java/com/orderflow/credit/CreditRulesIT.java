package com.orderflow.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.contracts.ReleaseCredit;
import com.orderflow.contracts.ReserveCredit;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reglas de SPEC §9 (credit), de punta a punta por Kafka. */
class CreditRulesIT extends AbstractIT {

  private static final Duration LIMIT = Duration.ofSeconds(15);

  private static ReserveCredit reserve(UUID orderId, String companyId, String amount) {
    return new ReserveCredit(orderId, companyId, new BigDecimal(amount), "BOB");
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
  void reservaAcumulaElUsado() throws Exception {
    creditLine("C1", "100.00");
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, reserve(orderId, "C1", "40.50"));

    awaitReplyTypes(orderId, "CreditReserved");
    assertThat(used("C1")).isEqualByComparingTo("40.50");
  }

  @Test
  void permiteLlegarExactamenteAlLimite() throws Exception {
    creditLine("C2", "100.00");
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, reserve(orderId, "C2", "100.00"));

    awaitReplyTypes(orderId, "CreditReserved");
    assertThat(used("C2")).isEqualByComparingTo("100.00");
  }

  @Test
  void excederElLimiteSeRechazaSinCambios() throws Exception {
    creditLine("C3", "100.00");
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, reserve(orderId, "C3", "100.01"));

    awaitReplyTypes(orderId, "CreditRejected");
    assertThat(reasonOfLast(orderId)).isEqualTo("CREDIT_LIMIT_EXCEEDED");
    assertThat(used("C3")).isEqualByComparingTo("0");
  }

  @Test
  void empresaSinLineaSeRechaza() throws Exception {
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, reserve(orderId, "SIN-LINEA", "1.00"));

    awaitReplyTypes(orderId, "CreditRejected");
    assertThat(reasonOfLast(orderId)).isEqualTo("NO_CREDIT_LINE");
  }

  @Test
  void reservaRepetidaConOtroMessageIdRespondeSinAcumular() throws Exception {
    creditLine("C5", "100.00");
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, reserve(orderId, "C5", "10.00"));
    publishCommand(orderId, reserve(orderId, "C5", "10.00"));

    awaitReplyTypes(orderId, "CreditReserved", "CreditReserved");
    assertThat(used("C5")).isEqualByComparingTo("10.00");
  }

  @Test
  void liberarDevuelveElCreditoYEsIdempotente() throws Exception {
    creditLine("C6", "100.00");
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, reserve(orderId, "C6", "30.00"));
    awaitReplyTypes(orderId, "CreditReserved");
    publishCommand(orderId, new ReleaseCredit(orderId));
    publishCommand(orderId, new ReleaseCredit(orderId));

    awaitReplyTypes(orderId, "CreditReserved", "CreditReleased", "CreditReleased");
    assertThat(used("C6")).isEqualByComparingTo("0");
  }

  @Test
  void liberarSinReservaDejaLapidaYLaReservaTardiaSeRechaza() throws Exception {
    creditLine("C7", "100.00");
    UUID orderId = UUID.randomUUID();

    publishCommand(orderId, new ReleaseCredit(orderId));
    awaitReplyTypes(orderId, "CreditReleased");

    publishCommand(orderId, reserve(orderId, "C7", "30.00"));

    awaitReplyTypes(orderId, "CreditReleased", "CreditRejected");
    assertThat(reasonOfLast(orderId)).isEqualTo("ALREADY_RELEASED");
    assertThat(used("C7")).isEqualByComparingTo("0");
  }
}
