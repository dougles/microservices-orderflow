package com.orderflow.messaging.outbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final JdbcTemplate jdbc;
  private final OutboxTracing tracing;
  private final OutboxProperties properties;
  private final Map<String, OutboxSender> senders;

  OutboxPublisher(
      JdbcTemplate jdbc,
      OutboxTracing tracing,
      OutboxProperties properties,
      List<OutboxSender> senders) {
    this.jdbc = jdbc;
    this.tracing = tracing;
    this.properties = properties;
    this.senders =
        senders.stream()
            .collect(Collectors.toMap(OutboxSender::destinationType, Function.identity()));
  }

  @Scheduled(fixedDelayString = "${orderflow.outbox.poll-interval}")
  @Transactional
  public void publishBatch() {
    List<OutboxRow> rows =
        jdbc.query(
            """
            SELECT id, destination_type, destination, message_key, message_type, payload, traceparent
            FROM outbox_message
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """,
            (rs, rowNum) ->
                new OutboxRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("destination_type"),
                    rs.getString("destination"),
                    rs.getString("message_key"),
                    rs.getString("message_type"),
                    rs.getString("payload"),
                    rs.getString("traceparent")),
            properties.batchSize());

    for (OutboxRow row : rows) {
      try {
        tracing.runInTrace(row.traceparent(), () -> send(row));
        jdbc.update("UPDATE outbox_message SET published_at = now() WHERE id = ?", row.id());
      } catch (Exception e) {
        log.warn("No se pudo publicar messageId={}: {}", row.id(), e.toString());
        jdbc.update(
            "UPDATE outbox_message SET attempts = attempts + 1, last_error = ? WHERE id = ?",
            e.toString(),
            row.id());
        break; // no adelantar mensajes posteriores en este ciclo
      }
    }
  }

  @Scheduled(cron = "0 0 3 * * *")
  public void purgePublished() {
    jdbc.update(
        "DELETE FROM outbox_message WHERE published_at < ?",
        OffsetDateTime.now().minus(properties.retention()));
  }

  private void send(OutboxRow row) throws Exception {
    OutboxSender sender = senders.get(row.destinationType());
    if (sender == null) {
      throw new IllegalStateException("Sin sender para " + row.destinationType());
    }
    sender.send(row);
  }
}
