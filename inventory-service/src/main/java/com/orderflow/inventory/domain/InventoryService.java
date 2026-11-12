package com.orderflow.inventory.domain;

import com.orderflow.contracts.ReleaseStock;
import com.orderflow.contracts.ReserveStock;
import com.orderflow.contracts.StockRejected;
import com.orderflow.contracts.StockReleased;
import com.orderflow.contracts.StockReserved;
import com.orderflow.contracts.Topics;
import com.orderflow.messaging.outbox.OutboxWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reglas de SPEC §9 (inventory). Corre en la transacción del listener: inbox + efecto + outbox. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryService {

  private static final String RESERVED = "RESERVED";
  private static final String RELEASED = "RELEASED";

  private final JdbcTemplate jdbc;
  private final OutboxWriter outbox;

  InventoryService(JdbcTemplate jdbc, OutboxWriter outbox) {
    this.jdbc = jdbc;
    this.outbox = outbox;
  }

  public void reserve(ReserveStock command) {
    UUID orderId = command.orderId();
    String status = statusOf(orderId);
    if (RESERVED.equals(status)) {
      reply(orderId, new StockReserved(orderId));
    } else if (RELEASED.equals(status)) {
      reply(orderId, new StockRejected(orderId, StockRejected.Reason.ALREADY_RELEASED));
    } else if (take(command.items())) {
      jdbc.update("INSERT INTO reservation (order_id, status) VALUES (?, ?)", orderId, RESERVED);
      for (ReserveStock.Item item : command.items()) {
        jdbc.update(
            "INSERT INTO reservation_item (order_id, sku, quantity) VALUES (?, ?, ?)",
            orderId,
            item.sku(),
            item.quantity());
      }
      reply(orderId, new StockReserved(orderId));
    } else {
      reply(orderId, new StockRejected(orderId, StockRejected.Reason.OUT_OF_STOCK));
    }
  }

  public void release(ReleaseStock command) {
    UUID orderId = command.orderId();
    String status = statusOf(orderId);
    if (RESERVED.equals(status)) {
      jdbc.update(
          """
          UPDATE stock s SET available = s.available + i.quantity
          FROM reservation_item i
          WHERE i.order_id = ? AND i.sku = s.sku
          """,
          orderId);
      jdbc.update(
          "UPDATE reservation SET status = ?, updated_at = now() WHERE order_id = ?",
          RELEASED,
          orderId);
    } else if (status == null) {
      // Lápida: un ReserveStock que llegue después no debe reservar.
      jdbc.update("INSERT INTO reservation (order_id, status) VALUES (?, ?)", orderId, RELEASED);
    }
    reply(orderId, new StockReleased(orderId));
  }

  /**
   * Descuenta todos los ítems o ninguno. El rollback es manual porque el rechazo debe commitear
   * inbox y respuesta; las filas tocadas siguen bloqueadas hasta el commit, así que nadie ve el
   * descuento parcial. Se ordena por sku para que dos pedidos no se bloqueen en orden inverso.
   */
  private boolean take(List<ReserveStock.Item> items) {
    var sorted = items.stream().sorted(Comparator.comparing(ReserveStock.Item::sku)).toList();
    var taken = new ArrayList<ReserveStock.Item>();
    for (ReserveStock.Item item : sorted) {
      int updated =
          jdbc.update(
              "UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?",
              item.quantity(),
              item.sku(),
              item.quantity());
      if (updated == 0) {
        taken.forEach(
            done ->
                jdbc.update(
                    "UPDATE stock SET available = available + ? WHERE sku = ?",
                    done.quantity(),
                    done.sku()));
        return false;
      }
      taken.add(item);
    }
    return true;
  }

  private String statusOf(UUID orderId) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT status FROM reservation WHERE order_id = ?", String.class, orderId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void reply(UUID orderId, Object message) {
    outbox.enqueueKafka(Topics.INVENTORY_REPLIES, orderId.toString(), message);
  }
}
