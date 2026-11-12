package com.orderflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * F09: la fila se commitea en {@code @BeforeAll}, antes de que exista el contexto de Spring, así que
 * no hay publisher corriendo. El publisher arranca con el contexto.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext
class F09PublisherCrashIT {

  private static final String TOPIC = "f09.outbox";
  private static final UUID ROW_ID = UUID.randomUUID();
  private static final String KEY = UUID.randomUUID().toString();

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15-alpine");

  @Container
  @ServiceConnection
  static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.4.0");

  @Autowired JdbcTemplate jdbc;

  @BeforeAll
  static void commitRowWithPublisherStopped() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/messaging")
        .load()
        .migrate();

    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var insert =
            connection.prepareStatement(
                """
                INSERT INTO outbox_message
                  (id, destination_type, destination, message_key, message_type, payload)
                VALUES (?, 'KAFKA', ?, ?, 'ReleaseStock', CAST(? AS jsonb))
                """)) {
      insert.setObject(1, ROW_ID);
      insert.setString(2, TOPIC);
      insert.setString(3, KEY);
      insert.setString(4, "{\"orderId\": \"" + KEY + "\"}");
      assertThat(insert.executeUpdate()).isEqualTo(1);
    }

    try (var admin =
        Admin.create(
            Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get();
    }
  }

  @Test
  void laFilaCommiteadaSePublicaExactamenteUnaVezAlArrancarElPublisher() {
    var records = new ArrayList<ConsumerRecord<String, String>>();

    try (var consumer = testConsumer()) {
      consumer.subscribe(List.of(TOPIC));

      await()
          .atMost(Duration.ofSeconds(15))
          .pollInSameThread()
          .untilAsserted(
              () -> {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                assertThat(records).isNotEmpty();
                assertThat(publishedAt()).isNotNull();
              });

      // Un reenvío aparecería en los ciclos siguientes del publisher (cada 0,2 s).
      await()
          .during(Duration.ofSeconds(3))
          .atMost(Duration.ofSeconds(6))
          .pollInSameThread()
          .untilAsserted(
              () -> {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                assertThat(records).hasSize(1);
              });
    }

    var record = records.getFirst();
    assertThat(header(record, "message_id")).isEqualTo(ROW_ID.toString());
    assertThat(header(record, "message_type")).isEqualTo("ReleaseStock");
    assertThat(record.key()).isEqualTo(KEY);
  }

  private OffsetDateTime publishedAt() {
    return jdbc.queryForObject(
        "SELECT published_at FROM outbox_message WHERE id = ?", OffsetDateTime.class, ROW_ID);
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  private static KafkaConsumer<String, String> testConsumer() {
    return new KafkaConsumer<>(
        Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
  }
}
