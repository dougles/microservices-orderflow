package com.orderflow.order.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.contracts.CreditReserved;
import com.orderflow.contracts.Queues;
import com.orderflow.contracts.ShipmentScheduled;
import com.orderflow.contracts.StockReserved;
import com.orderflow.contracts.Topics;
import com.orderflow.order.AbstractIT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

/** SPEC §8.3: ShipmentScheduled → CONFIRMED y un InvoiceJob en {@code invoice-jobs} vía outbox. */
class InvoiceJobIT extends AbstractIT {

  private static final Duration LIMIT = Duration.ofSeconds(30);

  @Container
  static LocalStackContainer localstack =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
          .withServices("sqs");

  @Autowired KafkaTemplate<String, String> kafkaTemplate;
  @Autowired SqsAsyncClient sqs;

  @DynamicPropertySource
  static void awsProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.cloud.aws.sqs.endpoint", () -> localstack.getEndpoint().toString());
  }

  @BeforeAll
  static void createTopics() throws Exception {
    try (var admin =
        Admin.create(
            Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                      Topics.INVENTORY_COMMANDS,
                      Topics.CREDIT_COMMANDS,
                      Topics.SHIPPING_COMMANDS,
                      Topics.INVENTORY_REPLIES,
                      Topics.CREDIT_REPLIES,
                      Topics.SHIPPING_REPLIES)
                  .stream()
                  .map(name -> new NewTopic(name, 3, (short) 1))
                  .toList())
          .all()
          .get();
    }
  }

  @Test
  void confirmarEmiteInvoiceJobConLasLineasYElTotal() throws Exception {
    String queueUrl = sqs.createQueue(b -> b.queueName(Queues.INVOICE_JOBS)).get().queueUrl();
    stubPrices("SKU-0001", "10.00", "SKU-0002", "20.00");
    String id = createdOrderId("ACME", order(TODAY.plusDays(7), "SKU-0001", 2, "SKU-0002", 3));
    UUID orderId = UUID.fromString(id);

    reply(Topics.INVENTORY_REPLIES, orderId, new StockReserved(orderId));
    awaitSagaState(orderId, "AWAITING_CREDIT");
    reply(Topics.CREDIT_REPLIES, orderId, new CreditReserved(orderId));
    awaitSagaState(orderId, "AWAITING_SHIPMENT");
    reply(Topics.SHIPPING_REPLIES, orderId, new ShipmentScheduled(orderId, UUID.randomUUID()));
    awaitSagaState(orderId, "CONFIRMED");

    await()
        .atMost(LIMIT)
        .untilAsserted(
            () -> {
              List<Message> messages =
                  sqs.receiveMessage(
                          b -> b.queueUrl(queueUrl).messageAttributeNames("All").waitTimeSeconds(1))
                      .get()
                      .messages();
              assertThat(messages).hasSize(1);
              Message received = messages.getFirst();
              assertThat(received.messageAttributes().get("message_type").stringValue())
                  .isEqualTo("InvoiceJob");
              assertThat(received.messageAttributes().get("message_id").stringValue())
                  .isEqualTo(outboxId(orderId).toString());

              var job = JSON.readTree(received.body());
              assertThat(job.get("orderId").asString()).isEqualTo(id);
              assertThat(job.get("companyId").asString()).isEqualTo("ACME");
              assertThat(job.get("currency").asString()).isEqualTo("BOB");
              assertThat(job.get("total").decimalValue()).isEqualByComparingTo("80.00");
              assertThat(job.get("lines")).hasSize(2);
              // Las Lambdas leen el JSON con Jackson 2: la fecha debe ir como texto ISO-8601.
              assertThat(Instant.parse(job.get("confirmedAt").asString())).isNotNull();
            });
  }

  private UUID outboxId(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT id FROM outbox_message WHERE message_type = 'InvoiceJob' AND payload->>'orderId' = ?",
        UUID.class,
        orderId.toString());
  }

  private void awaitSagaState(UUID orderId, String state) {
    await()
        .atMost(LIMIT)
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT state FROM saga WHERE order_id = ?", String.class, orderId))
                    .isEqualTo(state));
  }

  private void reply(String topic, UUID orderId, Object message) throws Exception {
    var record = new ProducerRecord<>(topic, orderId.toString(), JSON.writeValueAsString(message));
    record
        .headers()
        .add("message_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    record
        .headers()
        .add("message_type", message.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
    kafkaTemplate.send(record).get();
  }
}
