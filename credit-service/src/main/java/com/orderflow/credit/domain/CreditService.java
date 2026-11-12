package com.orderflow.credit.domain;

import com.orderflow.contracts.CreditRejected;
import com.orderflow.contracts.CreditReleased;
import com.orderflow.contracts.CreditReserved;
import com.orderflow.contracts.ReleaseCredit;
import com.orderflow.contracts.ReserveCredit;
import com.orderflow.contracts.Topics;
import com.orderflow.messaging.outbox.OutboxWriter;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reglas de SPEC §9 (credit). Corre en la transacción del listener: inbox + efecto + outbox. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CreditService {

  private static final String RESERVED = "RESERVED";
  private static final String RELEASED = "RELEASED";

  private final JdbcTemplate jdbc;
  private final OutboxWriter outbox;

  CreditService(JdbcTemplate jdbc, OutboxWriter outbox) {
    this.jdbc = jdbc;
    this.outbox = outbox;
  }

  public void reserve(ReserveCredit command) {
    UUID orderId = command.orderId();
    String status = statusOf(orderId);
    if (RESERVED.equals(status)) {
      reply(orderId, new CreditReserved(orderId));
    } else if (RELEASED.equals(status)) {
      reply(orderId, new CreditRejected(orderId, CreditRejected.Reason.ALREADY_RELEASED));
    } else if (take(command)) {
      jdbc.update(
          "INSERT INTO credit_reservation (order_id, company_id, amount, status) VALUES (?, ?, ?, ?)",
          orderId,
          command.companyId(),
          command.amount(),
          RESERVED);
      reply(orderId, new CreditReserved(orderId));
    } else {
      reply(orderId, new CreditRejected(orderId, rejectionFor(command.companyId())));
    }
  }

  public void release(ReleaseCredit command) {
    UUID orderId = command.orderId();
    String status = statusOf(orderId);
    if (RESERVED.equals(status)) {
      jdbc.update(
          """
          UPDATE credit_line l SET used = l.used - r.amount
          FROM credit_reservation r
          WHERE r.order_id = ? AND r.company_id = l.company_id
          """,
          orderId);
      jdbc.update(
          "UPDATE credit_reservation SET status = ?, updated_at = now() WHERE order_id = ?",
          RELEASED,
          orderId);
    } else if (status == null) {
      // Lápida: un ReserveCredit que llegue después no debe reservar.
      jdbc.update(
          "INSERT INTO credit_reservation (order_id, status) VALUES (?, ?)", orderId, RELEASED);
    }
    reply(orderId, new CreditReleased(orderId));
  }

  private boolean take(ReserveCredit command) {
    return jdbc.update(
            "UPDATE credit_line SET used = used + ? WHERE company_id = ? AND used + ? <= credit_limit",
            command.amount(),
            command.companyId(),
            command.amount())
        == 1;
  }

  private CreditRejected.Reason rejectionFor(String companyId) {
    Integer lines =
        jdbc.queryForObject(
            "SELECT count(*) FROM credit_line WHERE company_id = ?", Integer.class, companyId);
    return lines != null && lines > 0
        ? CreditRejected.Reason.CREDIT_LIMIT_EXCEEDED
        : CreditRejected.Reason.NO_CREDIT_LINE;
  }

  private String statusOf(UUID orderId) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT status FROM credit_reservation WHERE order_id = ?", String.class, orderId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void reply(UUID orderId, Object message) {
    outbox.enqueueKafka(Topics.CREDIT_REPLIES, orderId.toString(), message);
  }
}
