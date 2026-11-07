package com.orderflow.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxMetrics {

  private static final long CACHE_NANOS = Duration.ofSeconds(5).toNanos();

  private final JdbcTemplate jdbc;
  private long lastReadNanos;
  private boolean hasValue;
  private long pending;

  OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
    this.jdbc = jdbc;
    Gauge.builder("orderflow.outbox.pending", this, OutboxMetrics::pending).register(registry);
  }

  private synchronized double pending() {
    long now = System.nanoTime();
    if (!hasValue || now - lastReadNanos >= CACHE_NANOS) {
      pending =
          jdbc.queryForObject(
              "SELECT count(*) FROM outbox_message WHERE published_at IS NULL", Long.class);
      lastReadNanos = now;
      hasValue = true;
    }
    return pending;
  }
}
