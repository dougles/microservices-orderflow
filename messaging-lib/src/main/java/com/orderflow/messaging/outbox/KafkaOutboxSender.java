package com.orderflow.messaging.outbox;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaOutboxSender implements OutboxSender {

  private static final long SEND_TIMEOUT_SECONDS = 10;

  private final KafkaTemplate<String, String> kafkaTemplate;

  KafkaOutboxSender(KafkaTemplate<String, String> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  @Override
  public String destinationType() {
    return OutboxWriter.KAFKA;
  }

  @Override
  public void send(OutboxRow row) throws Exception {
    // El header traceparent lo inyecta la observación de KafkaTemplate desde el span activo.
    var record = new ProducerRecord<>(row.destination(), null, row.messageKey(), row.payload());
    record.headers().add("message_id", bytes(row.id().toString()));
    record.headers().add("message_type", bytes(row.messageType()));
    kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
