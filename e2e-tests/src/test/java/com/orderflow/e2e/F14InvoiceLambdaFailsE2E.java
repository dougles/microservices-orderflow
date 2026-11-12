package com.orderflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.e2e.support.Aws;
import com.orderflow.e2e.support.E2EBase;
import com.orderflow.e2e.support.Prometheus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * F14 — Enviar a {@code invoice-jobs} un body que no es JSON: el mensaje llega a {@code
 * invoice-jobs-dlq} (4 min) y la alerta {@code InvoiceDlqNotEmpty} queda FIRING (5 min). Limpieza:
 * purgar la DLQ al final.
 *
 * <p>Los dos límites se cuentan desde el envío.
 */
class F14InvoiceLambdaFailsE2E extends E2EBase {

  private static final String QUEUE = "invoice-jobs";
  private static final String DLQ = "invoice-jobs-dlq";
  private static final String ALERT = "InvoiceDlqNotEmpty";

  @AfterEach
  void purgeDlq() {
    Aws.SQS.purgeQueue(b -> b.queueUrl(Aws.queueUrl(DLQ)));
  }

  @Test
  void unBodyQueNoEsJsonTerminaEnLaDlqYDisparaLaAlerta() {
    // Precondición: sin esto, una alerta previa haría pasar el test sin causa.
    assertThat(Aws.visibleMessages(DLQ)).as("mensajes previos en %s", DLQ).isZero();
    await()
        .atMost(Duration.ofMinutes(2))
        .pollInterval(Duration.ofSeconds(5))
        .until(() -> !Prometheus.alertFiring(ALERT));

    String body = "esto no es json " + UUID.randomUUID();
    Aws.SQS.sendMessage(b -> b.queueUrl(Aws.queueUrl(QUEUE)).messageBody(body));
    Instant sent = Instant.now();

    String dlqUrl = Aws.queueUrl(DLQ);
    await()
        .atMost(Duration.ofMinutes(4))
        .pollInterval(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                // Visibilidad 0: mirar el mensaje sin ocultarlo al monitor de la DLQ.
                assertThat(
                        Aws.SQS.receiveMessage(
                                b ->
                                    b.queueUrl(dlqUrl)
                                        .maxNumberOfMessages(10)
                                        .visibilityTimeout(0)
                                        .waitTimeSeconds(1))
                            .messages())
                    .anySatisfy(m -> assertThat(m.body()).isEqualTo(body)));

    Duration alertLimit = Duration.ofMinutes(5).minus(Duration.between(sent, Instant.now()));
    await()
        .atMost(alertLimit)
        .pollInterval(Duration.ofSeconds(5))
        .until(() -> Prometheus.alertFiring(ALERT));
  }
}
