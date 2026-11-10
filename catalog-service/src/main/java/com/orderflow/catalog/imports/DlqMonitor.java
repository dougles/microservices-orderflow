package com.orderflow.catalog.imports;

import com.orderflow.contracts.Queues;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Gauge {@code orderflow.sqs.dlq.messages{queue=catalog-import-dlq}} (SPEC §13). Si el sondeo
 * falla, conserva el último valor y loguea WARN.
 */
@Component
class DlqMonitor {

  static final String QUEUE = Queues.CATALOG_IMPORT_DLQ;
  private static final Logger log = LoggerFactory.getLogger(DlqMonitor.class);
  private static final long TIMEOUT_SECONDS = 5;

  private final SqsAsyncClient sqs;
  /** NaN hasta el primer sondeo exitoso: "sin dato" no es lo mismo que una DLQ vacía. */
  private final AtomicReference<Double> messages = new AtomicReference<>(Double.NaN);

  DlqMonitor(SqsAsyncClient sqs, MeterRegistry registry) {
    this.sqs = sqs;
    Gauge.builder("orderflow.sqs.dlq.messages", messages, AtomicReference::get)
        .tag("queue", QUEUE)
        .register(registry);
  }

  @Scheduled(fixedDelayString = "${orderflow.dlq-monitor.interval}")
  void poll() {
    try {
      String url =
          sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(QUEUE).build())
              .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .queueUrl();
      String count =
          sqs.getQueueAttributes(
                  GetQueueAttributesRequest.builder()
                      .queueUrl(url)
                      .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                      .build())
              .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .attributes()
              .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
      messages.set(Double.parseDouble(count));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.warn("no se pudo leer la DLQ {}; se conserva el último valor: {}", QUEUE, e.toString());
    }
  }
}
