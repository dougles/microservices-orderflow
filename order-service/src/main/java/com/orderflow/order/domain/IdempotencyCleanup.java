package com.orderflow.order.domain;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retención de los registros de idempotencia (SPEC §10, {@code orderflow.idempotency.retention}). */
@Component
class IdempotencyCleanup {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

  private final IdempotencyStore store;
  private final Clock clock;
  private final Duration retention;

  IdempotencyCleanup(
      IdempotencyStore store,
      Clock clock,
      @Value("${orderflow.idempotency.retention}") Duration retention) {
    this.store = store;
    this.clock = clock;
    this.retention = retention;
  }

  @Scheduled(cron = "0 0 * * * *")
  void purgeExpired() {
    int deleted = store.deleteCreatedBefore(clock.instant().minus(retention));
    if (deleted > 0) {
      log.info("registros de idempotencia vencidos eliminados: {}", deleted);
    }
  }
}
