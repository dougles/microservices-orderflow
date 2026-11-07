package com.orderflow.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

@SpringBootTest
@Testcontainers
@DirtiesContext
class OutboxIT {

  private static final Duration LIMIT = Duration.ofSeconds(15);

  record SampleMessage(UUID orderId, BigDecimal amount, LocalDate deliveryDate) {}

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15-alpine");

  @Container
  @ServiceConnection
  static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.4.0");

  @Container
  static LocalStackContainer localstack =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
          .withServices("sqs");

  @DynamicPropertySource
  static void awsProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.cloud.aws.sqs.endpoint", () -> localstack.getEndpoint().toString());
  }

  @Autowired OutboxWriter writer;
  @Autowired OutboxPublisher publisher;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;
  @Autowired MeterRegistry meterRegistry;
  @Autowired SqsAsyncClient sqs;

  @AfterEach
  void cleanOutbox() {
    jdbc.update("DELETE FROM outbox_message");
  }

  @Test
  void escribirSinTransaccionFalla() {
    assertThatThrownBy(() -> writer.enqueueKafka("outbox.it", "k", sample()))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void unRollbackNoDejaMensaje() {
    transaction.executeWithoutResult(
        status -> {
          writer.enqueueKafka("outbox.it", "k", sample());
          status.setRollbackOnly();
        });

    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_message", Long.class)).isZero();
  }

  @Test
  void publicaEnKafkaElJsonDelRecordConSusHeaders() {
    String topic = "outbox.it." + UUID.randomUUID();
    SampleMessage message = sample();
    UUID id = transaction.execute(status -> writer.enqueueKafka(topic, "key-1", message));

    try (var consumer = testConsumer()) {
      consumer.subscribe(List.of(topic));
      await()
          .atMost(LIMIT)
          .pollInSameThread()
          .untilAsserted(
              () -> {
                var records = consumer.poll(Duration.ofMillis(200));
                assertThat(records.count()).isEqualTo(1);
                var record = records.iterator().next();
                assertThat(record.key()).isEqualTo("key-1");
                assertThat(new String(record.headers().lastHeader("message_id").value()))
                    .isEqualTo(id.toString());
                assertThat(new String(record.headers().lastHeader("message_type").value()))
                    .isEqualTo("SampleMessage");
                assertThat(record.value())
                    .contains(message.orderId().toString(), "450.00", "\"2026-11-20\"");
              });
    }
    await().atMost(LIMIT).untilAsserted(() -> assertThat(publishedAt(id)).isNotNull());
  }

  @Test
  void publicaEnSqsConSusAtributos() throws Exception {
    String queue = "outbox-it-" + UUID.randomUUID();
    String queueUrl = sqs.createQueue(b -> b.queueName(queue)).get().queueUrl();
    SampleMessage message = sample();
    UUID id = transaction.execute(status -> writer.enqueueSqs(queue, message));

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
              var received = messages.getFirst();
              assertThat(received.messageAttributes().get("message_id").stringValue())
                  .isEqualTo(id.toString());
              assertThat(received.messageAttributes().get("message_type").stringValue())
                  .isEqualTo("SampleMessage");
              assertThat(received.body()).contains(message.orderId().toString(), "450.00");
              assertThat(received.body()).startsWith("{");
            });
    await().atMost(LIMIT).untilAsserted(() -> assertThat(publishedAt(id)).isNotNull());
  }

  @Test
  void unEnvioFallidoQuedaPendienteConElErrorYSeMideEnElGauge() {
    UUID id =
        transaction.execute(status -> writer.enqueueKafka("topic invalido!", "k", sample()));

    await()
        .atMost(LIMIT)
        .untilAsserted(
            () -> {
              var row =
                  jdbc.queryForMap(
                      "SELECT attempts, last_error, published_at FROM outbox_message WHERE id = ?",
                      id);
              assertThat((Integer) row.get("attempts")).isPositive();
              assertThat((String) row.get("last_error")).isNotBlank();
              assertThat(row.get("published_at")).isNull();
              assertThat(meterRegistry.get("orderflow.outbox.pending").gauge().value())
                  .isEqualTo(1.0);
            });
  }

  @Test
  void laLimpiezaBorraSoloLosPublicadosFueraDeRetencion() {
    UUID old = insertPublished(OffsetDateTime.now().minusDays(8));
    UUID recent = insertPublished(OffsetDateTime.now().minusDays(6));

    publisher.purgePublished();

    assertThat(exists(old)).isFalse();
    assertThat(exists(recent)).isTrue();
  }

  private UUID insertPublished(OffsetDateTime publishedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO outbox_message
          (id, destination_type, destination, message_type, payload, published_at)
        VALUES (?, 'KAFKA', 'outbox.it', 'SampleMessage', '{}'::jsonb, ?)
        """,
        id,
        publishedAt);
    return id;
  }

  private boolean exists(UUID id) {
    return jdbc.queryForObject("SELECT count(*) FROM outbox_message WHERE id = ?", Long.class, id)
        == 1;
  }

  private OffsetDateTime publishedAt(UUID id) {
    return jdbc.queryForObject(
        "SELECT published_at FROM outbox_message WHERE id = ?", OffsetDateTime.class, id);
  }

  private static SampleMessage sample() {
    return new SampleMessage(
        UUID.randomUUID(), new BigDecimal("450.00"), LocalDate.parse("2026-11-20"));
  }

  private static KafkaConsumer<String, String> testConsumer() {
    return new KafkaConsumer<>(
        Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.METADATA_MAX_AGE_CONFIG, 500,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
  }
}
