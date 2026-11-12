package com.orderflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderflow.contracts.CreditReserved;
import com.orderflow.contracts.ShipmentRejected;
import com.orderflow.contracts.StockReleased;
import com.orderflow.contracts.StockReserved;
import com.orderflow.contracts.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * F19 — Compensación sin respuesta. Saga en AWAITING_SHIPMENT con stock y crédito reservados
 * (respuestas inyectadas); luego ShipmentRejected; se responde StockReleased pero nunca
 * CreditReleased.
 *
 * <p>Esperado: COMPENSATION_FAILED; ReleaseCredit emitido 6 veces (1 + 5 reintentos); métrica +1;
 * API: status CANCELLED, reason SHIPPING_NO_CAPACITY.
 */
@TestPropertySource(
    properties = {
      "orderflow.saga.compensation-retry-interval=PT1S",
      "orderflow.saga.max-compensation-attempts=5"
    })
class F19CompensationWithoutReplyIT extends AbstractIT {

  private static final Duration LIMIT = Duration.ofSeconds(30);

  @Autowired KafkaTemplate<String, String> kafkaTemplate;
  @Autowired MeterRegistry registry;

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
  void compensacionAgotadaQuedaEnCompensationFailed() throws Exception {
    stubPrices("SKU-0001", "10.00");
    String id = createdOrderId("ACME", order(TODAY.plusDays(7), "SKU-0001", 2));
    UUID orderId = UUID.fromString(id);
    double failedBefore = registry.counter("orderflow.saga.compensation.failed").count();

    reply(Topics.INVENTORY_REPLIES, orderId, new StockReserved(orderId));
    awaitSagaState(orderId, "AWAITING_CREDIT");
    reply(Topics.CREDIT_REPLIES, orderId, new CreditReserved(orderId));
    awaitSagaState(orderId, "AWAITING_SHIPMENT");
    reply(
        Topics.SHIPPING_REPLIES,
        orderId,
        new ShipmentRejected(orderId, ShipmentRejected.Reason.NO_CAPACITY));
    awaitSagaState(orderId, "COMPENSATING");
    reply(Topics.INVENTORY_REPLIES, orderId, new StockReleased(orderId));

    awaitSagaState(orderId, "COMPENSATION_FAILED");
    await()
        .atMost(LIMIT)
        .untilAsserted(
            () -> {
              assertThat(count("outbox_message WHERE published_at IS NULL")).isZero();
              assertThat(releaseCreditCommands(orderId)).isEqualTo(6);
            });

    assertThat(registry.counter("orderflow.saga.compensation.failed").count() - failedBefore)
        .isEqualTo(1);
    String body =
        mvc.perform(get(ORDERS + "/{id}", id).with(buyer("ACME")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var json = JSON.readTree(body);
    assertThat(json.get("status").asString()).isEqualTo("CANCELLED");
    assertThat(json.get("reason").asString()).isEqualTo("SHIPPING_NO_CAPACITY");
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

  private long releaseCreditCommands(UUID orderId) {
    try (var consumer =
        new KafkaConsumer<String, String>(
            Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
      var partitions =
          consumer.partitionsFor(Topics.CREDIT_COMMANDS).stream()
              .map(p -> new TopicPartition(p.topic(), p.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      var end = consumer.endOffsets(partitions);
      long matches = 0;
      while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
        for (var record : consumer.poll(Duration.ofMillis(200))) {
          var type = record.headers().lastHeader("message_type");
          if (orderId.toString().equals(record.key())
              && "ReleaseCredit".equals(new String(type.value(), StandardCharsets.UTF_8))) {
            matches++;
          }
        }
      }
      return matches;
    }
  }
}
