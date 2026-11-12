package com.orderflow.catalog.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.catalog.AbstractIT;
import com.orderflow.contracts.Queues;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** Consumidor de catalog-import: upsert por sku (SPEC §10) y reintento hasta DLQ si falla. */
class CatalogImportListenerIT extends AbstractIT {

  private static final Duration LIMIT = Duration.ofSeconds(30);

  @Autowired SqsAsyncClient sqs;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void cleanUp() {
    jdbc.update("DELETE FROM product WHERE sku LIKE 'IT-%'");
  }

  @Test
  void unSkuNuevoSeInsertaActivo() throws Exception {
    send(row("IT-IMP-1", "Importado", "12.50"));

    await()
        .atMost(LIMIT)
        .untilAsserted(
            () ->
                assertThat(product("IT-IMP-1"))
                    .containsEntry("name", "Importado")
                    .containsEntry("base_price", new BigDecimal("12.50"))
                    .containsEntry("active", true));
  }

  @Test
  void unSkuExistenteSeActualizaYQuedaActivo() throws Exception {
    jdbc.update(
        "INSERT INTO product (sku, name, base_price, active) VALUES ('IT-IMP-2', 'Viejo', 1.00, FALSE)");

    send(row("IT-IMP-2", "Nuevo", "7.00"));

    await()
        .atMost(LIMIT)
        .untilAsserted(
            () ->
                assertThat(product("IT-IMP-2"))
                    .containsEntry("name", "Nuevo")
                    .containsEntry("base_price", new BigDecimal("7.00"))
                    .containsEntry("active", true));
  }

  /**
   * Si el upsert lanza, el mensaje no se borra: queda en vuelo hasta que venza su visibilidad y
   * SQS lo reentrega (y tras maxReceiveCount lo mueve a la DLQ, por la RedrivePolicy del init).
   */
  @Test
  void unMensajeQueFallaNoSeBorraDeLaCola() throws Exception {
    String queueUrl = queueUrl();

    // basePrice negativo: el CHECK de la tabla hace fallar el upsert.
    send(row("IT-IMP-BAD", "Invalido", "-1.00"));

    await().atMost(LIMIT).until(() -> messagesInFlight(queueUrl) == 1);
    // Recibido por el listener y fallido: sigue en la cola, no se borró.
    await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(5))
        .until(() -> messagesInFlight(queueUrl) == 1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM product WHERE sku = 'IT-IMP-BAD'", Long.class))
        .isZero();
  }

  private long messagesInFlight(String queueUrl) throws Exception {
    String count =
        sqs.getQueueAttributes(
                b ->
                    b.queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
            .get()
            .attributes()
            .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE);
    return Long.parseLong(count);
  }

  private static String row(String sku, String name, String basePrice) {
    return """
        {"sku":"%s","name":"%s","basePrice":%s,"sourceFile":"incoming/it.csv","line":2}
        """
        .formatted(sku, name, basePrice);
  }

  private String queueUrl() throws Exception {
    return sqs.getQueueUrl(b -> b.queueName(Queues.CATALOG_IMPORT)).get().queueUrl();
  }

  private void send(String body) throws Exception {
    String queueUrl = queueUrl();
    sqs.sendMessage(
            b ->
                b.queueUrl(queueUrl)
                    .messageBody(body)
                    .messageAttributes(
                        Map.of(
                            "message_id", string(UUID.randomUUID().toString()),
                            "message_type", string("CatalogImportRow"))))
        .get();
  }

  private static MessageAttributeValue string(String value) {
    return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
  }

  private Map<String, Object> product(String sku) {
    var rows =
        jdbc.queryForList("SELECT name, base_price, active FROM product WHERE sku = ?", sku);
    return rows.isEmpty() ? Map.of() : rows.getFirst();
  }
}
