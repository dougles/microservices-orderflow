package com.orderflow.catalog.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

class DlqMonitorTest {

  private final SqsAsyncClient sqs = mock(SqsAsyncClient.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final DlqMonitor monitor = new DlqMonitor(sqs, registry);

  private double gauge() {
    return registry
        .get("orderflow.sqs.dlq.messages")
        .tag("queue", "catalog-import-dlq")
        .gauge()
        .value();
  }

  private void dlqHas(String count) {
    when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
        .thenReturn(
            CompletableFuture.completedFuture(
                GetQueueUrlResponse.builder().queueUrl("http://sqs/catalog-import-dlq").build()));
    when(sqs.getQueueAttributes(any(GetQueueAttributesRequest.class)))
        .thenReturn(
            CompletableFuture.completedFuture(
                GetQueueAttributesResponse.builder()
                    .attributes(Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, count))
                    .build()));
  }

  @Test
  void sinSondeoTodaviaNoHayDato() {
    assertThat(gauge()).isNaN();
  }

  @Test
  void elGaugeMuestraLosMensajesDeLaDlq() {
    dlqHas("4");

    monitor.poll();

    assertThat(gauge()).isEqualTo(4.0);
  }

  @Test
  void siElSondeoFallaSeConservaElUltimoValor() {
    dlqHas("1");
    monitor.poll();
    when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("localstack caído")));

    monitor.poll();

    assertThat(gauge()).isEqualTo(1.0);
  }
}
