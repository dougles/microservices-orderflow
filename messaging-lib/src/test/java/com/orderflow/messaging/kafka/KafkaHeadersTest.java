package com.orderflow.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaHeadersTest {

  @Test
  void leeLosHeadersObligatorios() {
    UUID id = UUID.randomUUID();
    var record = record();
    record.headers().add("message_id", id.toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("message_type", "ReserveStock".getBytes(StandardCharsets.UTF_8));

    assertThat(KafkaHeaders.messageId(record)).isEqualTo(id);
    assertThat(KafkaHeaders.messageType(record)).isEqualTo("ReserveStock");
  }

  @Test
  void messageIdAusenteNoEsReintentable() {
    assertThatThrownBy(() -> KafkaHeaders.messageId(record()))
        .isInstanceOf(NonRetryableMessageException.class);
  }

  @Test
  void messageIdInvalidoNoEsReintentable() {
    var record = record();
    record.headers().add("message_id", "no-es-uuid".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> KafkaHeaders.messageId(record))
        .isInstanceOf(NonRetryableMessageException.class);
  }

  @Test
  void messageTypeAusenteNoEsReintentable() {
    assertThatThrownBy(() -> KafkaHeaders.messageType(record()))
        .isInstanceOf(NonRetryableMessageException.class);
  }

  private static ConsumerRecord<String, String> record() {
    return new ConsumerRecord<>("topic", 0, 0L, "key", "value");
  }
}
