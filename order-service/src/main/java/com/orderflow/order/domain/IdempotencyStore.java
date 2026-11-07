package com.orderflow.order.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Registro de idempotencia de {@code POST /api/orders}, PK (companyId, key) (SPEC §10). */
@Repository
class IdempotencyStore {

  record Entry(String requestHash, int responseStatus, String responseBody) {}

  private final JdbcTemplate jdbc;

  IdempotencyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Optional<Entry> find(String companyId, UUID key) {
    return jdbc
        .query(
            """
            SELECT request_hash, response_status, response_body FROM idempotency_key
            WHERE company_id = ? AND idempotency_key = ?
            """,
            (rs, n) -> new Entry(rs.getString(1), rs.getInt(2), rs.getString(3)),
            companyId,
            key)
        .stream()
        .findFirst();
  }

  /**
   * Inserta el registro. Si otra transacción ya insertó la misma PK y no ha terminado, Postgres
   * espera a que termine: si commitea, devuelve {@code false}; si hace rollback, inserta.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  boolean insertIfAbsent(
      String companyId, UUID key, Entry entry, Instant now) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO idempotency_key
              (company_id, idempotency_key, request_hash, response_status, response_body, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (company_id, idempotency_key) DO NOTHING
            """,
            companyId,
            key,
            entry.requestHash(),
            entry.responseStatus(),
            entry.responseBody(),
            Timestamp.from(now));
    return inserted == 1;
  }

  int deleteCreatedBefore(Instant limit) {
    return jdbc.update("DELETE FROM idempotency_key WHERE created_at < ?", Timestamp.from(limit));
  }
}
