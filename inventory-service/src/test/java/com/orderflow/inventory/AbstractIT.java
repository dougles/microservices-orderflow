package com.orderflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.contracts.Topics;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext
public abstract class AbstractIT {

  protected static final JsonMapper JSON = JsonMapper.shared();

  @Container
  @ServiceConnection
  protected static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15-alpine");

  @Container
  @ServiceConnection
  static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.4.0");

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected KafkaTemplate<String, String> kafkaTemplate;
  @Autowired protected MockMvc mvc;

  /** Un registro leído de Kafka. {@code originalTimestamp} solo existe en los del DLT. */
  public record Received(
      String type, String key, String value, long timestamp, Long originalTimestamp) {

    /** Tiempo entre la publicación original y la llegada al DLT. */
    public long millisInOriginalTopic() {
      return timestamp - originalTimestamp;
    }
  }

  @BeforeAll
  static void createTopics() throws Exception {
    try (var admin =
        Admin.create(
            Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
      var existing = admin.listTopics().names().get();
      var missing =
          List.of(
                  Topics.INVENTORY_COMMANDS,
                  Topics.INVENTORY_COMMANDS_DLT,
                  Topics.INVENTORY_REPLIES,
                  Topics.INVENTORY_REPLIES_DLT)
              .stream()
              .filter(name -> !existing.contains(name))
              .map(name -> new NewTopic(name, 3, (short) 1))
              .toList();
      admin.createTopics(missing).all().get();
    }
  }

  @AfterEach
  void cleanDatabase() {
    jdbc.update("DELETE FROM reservation_item");
    jdbc.update("DELETE FROM reservation");
    jdbc.update("DELETE FROM stock");
  }

  protected void stock(String sku, int available) {
    jdbc.update(
        "INSERT INTO stock (sku, available) VALUES (?, ?)"
            + " ON CONFLICT (sku) DO UPDATE SET available = EXCLUDED.available",
        sku,
        available);
  }

  protected int available(String sku) {
    return jdbc.queryForObject("SELECT available FROM stock WHERE sku = ?", Integer.class, sku);
  }

  /** Publica en {@code inventory.commands} con un {@code message_id} nuevo y lo devuelve. */
  protected UUID publishCommand(UUID orderId, Object message) throws Exception {
    UUID messageId = UUID.randomUUID();
    publishCommand(messageId, orderId, message);
    return messageId;
  }

  protected void publishCommand(UUID messageId, UUID orderId, Object message) throws Exception {
    publishRaw(
        messageId, orderId, message.getClass().getSimpleName(), JSON.writeValueAsString(message));
  }

  protected void publishRaw(UUID messageId, UUID orderId, String type, String payload)
      throws Exception {
    var record =
        new ProducerRecord<>(Topics.INVENTORY_COMMANDS, orderId.toString(), payload);
    record.headers().add("message_id", messageId.toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("message_type", type.getBytes(StandardCharsets.UTF_8));
    kafkaTemplate.send(record).get();
  }

  /** Todo lo publicado hasta ahora en el topic para {@code key}; lee desde el principio. */
  protected List<Received> readAll(String topic, UUID key) {
    var result = new ArrayList<Received>();
    try (var consumer = newConsumer()) {
      var partitions =
          consumer.partitionsFor(topic).stream()
              .map(p -> new TopicPartition(p.topic(), p.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      var end = consumer.endOffsets(partitions);
      while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
        for (var record : consumer.poll(Duration.ofMillis(200))) {
          if (key.toString().equals(record.key())) {
            var type = record.headers().lastHeader("message_type");
            var original = record.headers().lastHeader("kafka_dlt-original-timestamp");
            result.add(
                new Received(
                    type == null ? null : new String(type.value(), StandardCharsets.UTF_8),
                    record.key(),
                    record.value(),
                    record.timestamp(),
                    original == null ? null : ByteBuffer.wrap(original.value()).getLong()));
          }
        }
      }
    }
    return result;
  }

  protected List<Received> replies(UUID orderId) {
    return readAll(Topics.INVENTORY_REPLIES, orderId);
  }

  /** Espera a que el listener commitee todo {@code inventory.commands} y el outbox se vacíe. */
  protected void awaitCommandsConsumedAndPublished(Duration limit) {
    await()
        .atMost(limit)
        .untilAsserted(
            () -> {
              try (var consumer = newConsumer("inventory-service")) {
                var partitions =
                    consumer.partitionsFor(Topics.INVENTORY_COMMANDS).stream()
                        .map(p -> new TopicPartition(p.topic(), p.partition()))
                        .collect(Collectors.toSet());
                var end = consumer.endOffsets(partitions);
                var committed = consumer.committed(partitions);
                for (TopicPartition partition : partitions) {
                  var offset = committed.get(partition);
                  assertThat(offset == null ? 0 : offset.offset()).isEqualTo(end.get(partition));
                }
              }
              assertThat(
                      jdbc.queryForObject(
                          "SELECT count(*) FROM outbox_message WHERE published_at IS NULL",
                          Integer.class))
                  .isZero();
            });
  }

  private static KafkaConsumer<String, String> newConsumer() {
    return newConsumer("it-" + UUID.randomUUID());
  }

  private static KafkaConsumer<String, String> newConsumer(String groupId) {
    return new KafkaConsumer<>(
        Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, groupId,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
  }
}
