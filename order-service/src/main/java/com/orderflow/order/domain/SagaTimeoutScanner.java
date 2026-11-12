package com.orderflow.order.domain;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Scanner de {@code step_deadline} vencidos (SPEC §8.2). */
@Component
class SagaTimeoutScanner {

  private static final Logger log = LoggerFactory.getLogger(SagaTimeoutScanner.class);

  private static final String DUE =
      """
      SELECT order_id FROM saga
      WHERE step_deadline < now()
        AND state IN ('AWAITING_STOCK','AWAITING_CREDIT','AWAITING_SHIPMENT','COMPENSATING')
      ORDER BY step_deadline
      LIMIT 50
      FOR UPDATE SKIP LOCKED
      """;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final SagaOrchestrator orchestrator;

  SagaTimeoutScanner(JdbcTemplate jdbc, TransactionTemplate tx, SagaOrchestrator orchestrator) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.orchestrator = orchestrator;
  }

  @Scheduled(fixedDelayString = "${orderflow.saga.timeout-check-interval}")
  void scan() {
    List<UUID> due = tx.execute(status -> jdbc.queryForList(DUE, UUID.class));
    for (UUID orderId : due) {
      try {
        orchestrator.onTimeout(orderId);
      } catch (RuntimeException e) {
        // Un fallo en una saga no frena a las demás; el próximo escaneo la reintenta.
        log.error("timeout falló orderId={}", orderId, e);
      }
    }
  }
}
