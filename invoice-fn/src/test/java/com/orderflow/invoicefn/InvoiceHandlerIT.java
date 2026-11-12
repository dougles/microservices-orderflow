package com.orderflow.invoicefn;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Handler invocado directamente contra S3 real de LocalStack (skill aws-lambda). */
@Testcontainers
class InvoiceHandlerIT {

  @Container
  static LocalStackContainer localstack =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
          .withServices("s3");

  static S3Client s3;

  @BeforeAll
  static void bucket() {
    s3 =
        S3Client.builder()
            .endpointOverride(localstack.getEndpoint())
            .region(Region.of(localstack.getRegion()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        localstack.getAccessKey(), localstack.getSecretKey())))
            .forcePathStyle(true)
            .build();
    s3.createBucket(b -> b.bucket(InvoiceHandler.BUCKET));
  }

  private static SQSEvent.SQSMessage message(String body) {
    var message = new SQSEvent.SQSMessage();
    message.setMessageId(UUID.randomUUID().toString());
    message.setBody(body);
    var traceparent = new SQSEvent.MessageAttribute();
    traceparent.setDataType("String");
    traceparent.setStringValue("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    message.setMessageAttributes(Map.of("traceparent", traceparent));
    return message;
  }

  private static String invoiceJob(UUID orderId, String companyId) {
    return """
        {"orderId":"%s","companyId":"%s",
         "lines":[{"sku":"SKU-0001","quantity":2,"unitPrice":9.00}],
         "total":18.00,"currency":"BOB","confirmedAt":"2026-11-09T14:05:31Z"}
        """
        .formatted(orderId, companyId);
  }

  @Test
  void unMensajeInvalidoSeReportaYElRestoDelBatchSeProcesa() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    var bad = message("esto no es json");
    var event = new SQSEvent();
    event.setRecords(
        List.of(message(invoiceJob(first, "ACME")), bad, message(invoiceJob(second, "GLOBEX"))));

    SQSBatchResponse response = new InvoiceHandler(s3).handleRequest(event, null);

    assertThat(response.getBatchItemFailures())
        .extracting(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
        .containsExactly(bad.getMessageId());
    assertPdf("invoices/ACME/" + first + ".pdf");
    assertPdf("invoices/GLOBEX/" + second + ".pdf");
  }

  @Test
  void reprocesarSobrescribeElMismoObjeto() {
    UUID orderId = UUID.randomUUID();
    var event = new SQSEvent();
    event.setRecords(List.of(message(invoiceJob(orderId, "ACME"))));
    var handler = new InvoiceHandler(s3);

    handler.handleRequest(event, null);
    SQSBatchResponse again = handler.handleRequest(event, null);

    assertThat(again.getBatchItemFailures()).isEmpty();
    assertThat(
            s3.listObjectsV2(b -> b.bucket(InvoiceHandler.BUCKET).prefix("invoices/ACME/" + orderId))
                .keyCount())
        .isEqualTo(1);
  }

  private static void assertPdf(String key) {
    var object = s3.getObjectAsBytes(b -> b.bucket(InvoiceHandler.BUCKET).key(key));
    assertThat(object.response().contentType()).isEqualTo("application/pdf");
    assertThat(new String(Arrays.copyOf(object.asByteArray(), 4), StandardCharsets.US_ASCII))
        .isEqualTo("%PDF");
  }
}
