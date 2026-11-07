package com.orderflow.messaging.kafka;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/** Lectura de los headers obligatorios de SPEC §7.1. */
public final class KafkaHeaders {

  static final String MESSAGE_ID = "message_id";
  static final String MESSAGE_TYPE = "message_type";
  static final String TRACEPARENT = "traceparent";

  private KafkaHeaders() {}

  public static UUID messageId(ConsumerRecord<?, ?> record) {
    String value = required(record, MESSAGE_ID);
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new NonRetryableMessageException("Header message_id inválido");
    }
  }

  public static String messageType(ConsumerRecord<?, ?> record) {
    return required(record, MESSAGE_TYPE);
  }

  private static String required(ConsumerRecord<?, ?> record, String name) {
    var header = record.headers().lastHeader(name);
    if (header == null || header.value() == null) {
      throw new NonRetryableMessageException("Falta el header " + name);
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
