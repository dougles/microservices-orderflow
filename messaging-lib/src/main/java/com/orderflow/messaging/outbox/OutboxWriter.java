package com.orderflow.messaging.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Único punto de entrada para emitir mensajes desde código de negocio. */
public class OutboxWriter {

  static final String KAFKA = "KAFKA";
  static final String SQS = "SQS";

  private final JdbcTemplate jdbc;
  private final OutboxTracing tracing;

  OutboxWriter(JdbcTemplate jdbc, OutboxTracing tracing) {
    this.jdbc = jdbc;
    this.tracing = tracing;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID enqueueKafka(String topic, String key, Object message) {
    return insert(KAFKA, topic, key, message);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID enqueueSqs(String queue, Object message) {
    return insert(SQS, queue, null, message);
  }

  private UUID insert(String destinationType, String destination, String key, Object message) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO outbox_message
          (id, destination_type, destination, message_key, message_type, payload, traceparent)
        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
        """,
        id,
        destinationType,
        destination,
        key,
        message.getClass().getSimpleName(),
        JsonMapper.shared().writeValueAsString(message),
        tracing.currentTraceparent());
    return id;
  }
}
