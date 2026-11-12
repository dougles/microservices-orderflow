package com.orderflow.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.kafka.consumer.auto-offset-reset=earliest")
@Testcontainers
@DirtiesContext
class KafkaConsumerIT {

  private static final String CONSUMER = "messaging-lib-it";
  private static final String TOPIC = "consumer.it";
  private static final String REPLAY_TOPIC = "replay.it";
  private static final Duration LIMIT = Duration.ofSeconds(60);
  private static final String VALID_JSON = "{\"ok\":true}";

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15-alpine");

  @Container
  @ServiceConnection
  static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.4.0");

  @BeforeAll
  static void createTopics() throws Exception {
    try (var admin =
        Admin.create(
            Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(TOPIC, TOPIC + ".DLT", REPLAY_TOPIC, REPLAY_TOPIC + ".DLT").stream()
                  .map(name -> new NewTopic(name, 3, (short) 1))
                  .toList())
          .all()
          .get();
    }
  }

  @TestConfiguration
  static class ListenerConfiguration {
    @Bean
    TestListener testListener(InboxRepository inbox) {
      return new TestListener(inbox);
    }
  }

  public static class TestListener {

    private final InboxRepository inbox;
    final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();
    final Map<UUID, AtomicInteger> effects = new ConcurrentHashMap<>();

    TestListener(InboxRepository inbox) {
      this.inbox = inbox;
    }

    @KafkaListener(topics = {TOPIC, REPLAY_TOPIC}, groupId = CONSUMER)
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
      UUID id = KafkaHeaders.messageId(record);
      attempts.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
      if (!inbox.firstTime(id, CONSUMER)) {
        return;
      }
      switch (record.value()) {
        case "retryable" -> throw new IllegalStateException("fallo transitorio");
        case "non-retryable" -> throw new NonRetryableMessageException("fallo definitivo");
        default -> JsonMapper.shared().readTree(record.value());
      }
      effects.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
    }

    int attempts(UUID id) {
      return attempts.getOrDefault(id, new AtomicInteger()).get();
    }

    int effects(UUID id) {
      return effects.getOrDefault(id, new AtomicInteger()).get();
    }
  }

  @Autowired KafkaTemplate<String, String> template;
  @Autowired TestListener listener;
  @Autowired InboxRepository inbox;
  @Autowired DltReplayController controller;
  @Autowired JdbcTemplate jdbc;

  @Test
  void unDuplicadoNoRepiteElEfecto() throws Exception {
    UUID id = UUID.randomUUID();

    publish(TOPIC, id, VALID_JSON);
    publish(TOPIC, id, VALID_JSON);

    await().atMost(LIMIT).until(() -> listener.attempts(id) == 2);
    assertThat(listener.effects(id)).isEqualTo(1);
  }

  @Test
  void unFalloReintentableHace4IntentosYVaAlDltEnLaMismaParticion() throws Exception {
    UUID id = UUID.randomUUID();

    int partition = publish(TOPIC, id, "retryable");

    var inDlt = awaitInDlt(id);
    assertThat(inDlt.partition()).isEqualTo(partition);
    assertThat(inDlt.value()).isEqualTo("retryable");
    assertThat(listener.attempts(id)).isEqualTo(4);
    assertThat(listener.effects(id)).isZero();
    assertThat(inboxRows(id)).as("el rollback también deshace el inbox").isZero();
  }

  @Test
  void unFalloNoReintentableVaAlDltTrasUnIntento() throws Exception {
    UUID id = UUID.randomUUID();

    publish(TOPIC, id, "non-retryable");

    awaitInDlt(id);
    assertThat(listener.attempts(id)).isEqualTo(1);
  }

  @Test
  void unJsonInvalidoVaAlDltTrasUnIntentoYElSiguienteSeProcesa() throws Exception {
    UUID poison = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    String key = UUID.randomUUID().toString();

    publish(TOPIC, key, poison, "{no es json");
    publish(TOPIC, key, next, VALID_JSON);

    awaitInDlt(poison);
    assertThat(listener.attempts(poison)).isEqualTo(1);
    await().atMost(LIMIT).until(() -> listener.effects(next) == 1);
  }

  @Test
  void unMensajeSinMessageIdVaAlDltSinReintentos() throws Exception {
    String marker = "sin-id-" + UUID.randomUUID();

    template.send(new ProducerRecord<>(TOPIC, "k", marker)).get(10, TimeUnit.SECONDS);

    await()
        .atMost(LIMIT)
        .untilAsserted(
            () -> assertThat(readAll(TOPIC + ".DLT")).extracting(ConsumerRecord::value).contains(marker));
  }

  @Test
  void elReplayReinyectaConservandoSoloLosHeadersDeSpec() throws Exception {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    for (UUID id : List.of(first, second, third)) {
      publishToDlt(id);
    }

    assertThat(replay(2)).isEqualTo(2);
    assertThat(replay(100)).as("continúa donde quedó").isEqualTo(1);
    assertThat(replay(100)).as("el DLT quedó agotado").isZero();

    await()
        .atMost(LIMIT)
        .until(
            () ->
                listener.effects(first) == 1
                    && listener.effects(second) == 1
                    && listener.effects(third) == 1);

    var replayed = readAll(REPLAY_TOPIC);
    assertThat(replayed).hasSize(3);
    for (var record : replayed) {
      assertThat(headerNames(record))
          .containsExactlyInAnyOrder("message_id", "message_type", "traceparent");
      assertThat(header(record, "traceparent")).isEqualTo(traceparentOf(record));
      assertThat(record.value()).isEqualTo(VALID_JSON);
      assertThat(record.key()).isEqualTo(header(record, "message_id"));
    }

    // Reinyectar algo ya procesado: el inbox lo descarta.
    publishToDlt(first);
    assertThat(replay(100)).isEqualTo(1);
    await().atMost(LIMIT).until(() -> listener.attempts(first) == 2);
    assertThat(listener.effects(first)).isEqualTo(1);
  }

  @Test
  void elReplayDeUnTopicQueNoSeConsumeResponde404() {
    var response = controller.replay("inventory.commands", 100);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var problem = (ProblemDetail) response.getBody();
    assertThat(problem.getProperties()).containsEntry("code", "NOT_FOUND");
  }

  @Test
  void laLimpiezaBorraSoloLoQueExcedeLaRetencion() {
    UUID old = insertInbox(OffsetDateTime.now().minusDays(8));
    UUID recent = insertInbox(OffsetDateTime.now().minusDays(6));

    inbox.purgeExpired();

    assertThat(inboxRows(old)).isZero();
    assertThat(inboxRows(recent)).isEqualTo(1);
  }

  @SuppressWarnings("unchecked")
  private int replay(int max) {
    var response = controller.replay(REPLAY_TOPIC, max);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return ((Map<String, Integer>) response.getBody()).get("replayed");
  }

  private int publish(String topic, UUID id, String value) throws Exception {
    return publish(topic, UUID.randomUUID().toString(), id, value);
  }

  private int publish(String topic, String key, UUID id, String value) throws Exception {
    var record = new ProducerRecord<>(topic, null, key, value);
    record.headers().add("message_id", bytes(id.toString()));
    record.headers().add("message_type", bytes("Sample"));
    return template.send(record).get(10, TimeUnit.SECONDS).getRecordMetadata().partition();
  }

  private void publishToDlt(UUID id) throws Exception {
    var record = new ProducerRecord<>(REPLAY_TOPIC + ".DLT", null, id.toString(), VALID_JSON);
    record.headers().add("message_id", bytes(id.toString()));
    record.headers().add("message_type", bytes("Sample"));
    record.headers().add("traceparent", bytes(traceparent(id)));
    record.headers().add("kafka_dlt-exception-message", bytes("descartar"));
    template.send(record).get(10, TimeUnit.SECONDS);
  }

  private static String traceparentOf(ConsumerRecord<String, String> record) {
    return traceparent(UUID.fromString(header(record, "message_id")));
  }

  private static String traceparent(UUID id) {
    return "00-" + id.toString().replace("-", "") + "-b7ad6b7169203331-01";
  }

  private ConsumerRecord<String, String> awaitInDlt(UUID id) {
    var found = new ArrayList<ConsumerRecord<String, String>>();
    await()
        .atMost(LIMIT)
        .untilAsserted(
            () -> {
              found.clear();
              readAll(TOPIC + ".DLT").stream()
                  .filter(record -> id.toString().equals(header(record, "message_id")))
                  .forEach(found::add);
              assertThat(found).hasSize(1);
            });
    return found.getFirst();
  }

  private static List<ConsumerRecord<String, String>> readAll(String topic) {
    try (var consumer =
        new KafkaConsumer<String, String>(
            Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
      var partitions =
          consumer.partitionsFor(topic).stream()
              .map(info -> new TopicPartition(info.topic(), info.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      var end = consumer.endOffsets(partitions);
      var records = new ArrayList<ConsumerRecord<String, String>>();
      while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
        consumer.poll(Duration.ofMillis(200)).forEach(records::add);
      }
      return records;
    }
  }

  private static Set<String> headerNames(ConsumerRecord<String, String> record) {
    return StreamSupport.stream(record.headers().spliterator(), false)
        .map(header -> header.key())
        .collect(Collectors.toSet());
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private long inboxRows(UUID id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM inbox_message WHERE message_id = ?", Long.class, id);
  }

  private UUID insertInbox(OffsetDateTime processedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inbox_message (message_id, consumer, processed_at) VALUES (?, ?, ?)",
        id,
        CONSUMER,
        processedAt);
    return id;
  }
}
