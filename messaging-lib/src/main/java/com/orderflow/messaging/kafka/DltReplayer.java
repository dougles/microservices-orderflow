package com.orderflow.messaging.kafka;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;

/** Reinyecta mensajes de {@code <topic>.DLT} en {@code <topic>} (SPEC §5.5). */
public class DltReplayer {

  static final int MAX_REPLAY = 1000;

  private static final String DLT_SUFFIX = ".DLT";
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
  private static final long SEND_TIMEOUT_SECONDS = 10;
  private static final Set<String> KEPT_HEADERS =
      Set.of(KafkaHeaders.MESSAGE_ID, KafkaHeaders.MESSAGE_TYPE, KafkaHeaders.TRACEPARENT);

  private final ConsumerFactory<String, String> consumerFactory;
  private final ProducerFactory<String, String> producerFactory;
  private final KafkaListenerEndpointRegistry listeners;
  private final String groupId;

  DltReplayer(
      ConsumerFactory<String, String> consumerFactory,
      ProducerFactory<String, String> producerFactory,
      KafkaListenerEndpointRegistry listeners,
      String applicationName) {
    this.consumerFactory = consumerFactory;
    this.producerFactory = producerFactory;
    this.listeners = listeners;
    this.groupId = applicationName + "-dlt-replay";
  }

  public boolean consumes(String topic) {
    return listeners.getListenerContainers().stream()
        .map(container -> container.getContainerProperties().getTopics())
        .filter(Objects::nonNull)
        .flatMap(Arrays::stream)
        .anyMatch(topic::equals);
  }

  public int replay(String topic, int max) {
    int limit = effectiveLimit(max);
    if (limit == 0) {
      return 0;
    }
    // Producer directo: sin la observación de KafkaTemplate, traceparent se copia tal cual.
    try (Consumer<String, String> consumer = newConsumer(limit);
        Producer<String, String> producer = producerFactory.createProducer()) {
      // assign y no subscribe: sin rebalanceo, un poll vacío significa que el DLT está agotado.
      consumer.assign(
          consumer.partitionsFor(topic + DLT_SUFFIX).stream()
              .map(info -> new TopicPartition(info.topic(), info.partition()))
              .toList());
      return republish(consumer, producer, topic, limit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Replay interrumpido", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("No se pudo reinyectar en " + topic, e);
    }
  }

  static int effectiveLimit(int max) {
    return Math.max(0, Math.min(max, MAX_REPLAY));
  }

  private int republish(
      Consumer<String, String> consumer,
      Producer<String, String> producer,
      String topic,
      int limit)
      throws InterruptedException, ExecutionException, TimeoutException {
    var offsets = new HashMap<TopicPartition, OffsetAndMetadata>();
    int replayed = 0;
    while (replayed < limit) {
      var records = consumer.poll(POLL_TIMEOUT);
      if (records.isEmpty()) {
        break;
      }
      for (ConsumerRecord<String, String> record : records) {
        if (replayed == limit) {
          break;
        }
        producer.send(copyOf(record, topic)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        offsets.put(
            new TopicPartition(record.topic(), record.partition()),
            new OffsetAndMetadata(record.offset() + 1));
        replayed++;
      }
    }
    if (!offsets.isEmpty()) {
      consumer.commitSync(offsets);
    }
    return replayed;
  }

  private static ProducerRecord<String, String> copyOf(
      ConsumerRecord<String, String> record, String topic) {
    var copy = new ProducerRecord<>(topic, null, record.key(), record.value());
    record.headers().forEach(
        header -> {
          if (KEPT_HEADERS.contains(header.key())) {
            copy.headers().add(header);
          }
        });
    return copy;
  }

  private Consumer<String, String> newConsumer(int limit) {
    var overrides = new Properties();
    overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    overrides.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, limit);
    return consumerFactory.createConsumer(groupId, null, null, overrides);
  }
}
