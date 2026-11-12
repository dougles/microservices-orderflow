package com.orderflow.messaging.kafka;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public class InboxRepository {

  private final JdbcTemplate jdbc;
  private final InboxProperties properties;

  InboxRepository(JdbcTemplate jdbc, InboxProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  /** Debe llamarse en la misma transacción que el efecto del mensaje. */
  public boolean firstTime(UUID messageId, String consumer) {
    return jdbc.update(
            """
            INSERT INTO inbox_message (message_id, consumer) VALUES (?, ?)
            ON CONFLICT DO NOTHING
            """,
            messageId,
            consumer)
        == 1;
  }

  @Scheduled(cron = "0 15 3 * * *")
  public void purgeExpired() {
    jdbc.update(
        "DELETE FROM inbox_message WHERE processed_at < ?",
        OffsetDateTime.now().minus(properties.retention()));
  }
}
