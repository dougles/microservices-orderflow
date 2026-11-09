package com.orderflow.shipping.domain;

import com.orderflow.contracts.CancelShipment;
import com.orderflow.contracts.ScheduleShipment;
import com.orderflow.contracts.ShipmentCancelled;
import com.orderflow.contracts.ShipmentRejected;
import com.orderflow.contracts.ShipmentScheduled;
import com.orderflow.contracts.Topics;
import com.orderflow.messaging.outbox.OutboxWriter;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reglas de SPEC §9 (shipping). Corre en la transacción del listener: inbox + efecto + outbox. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ShippingService {

  private static final String SCHEDULED = "SCHEDULED";
  private static final String CANCELLED = "CANCELLED";

  private record Shipment(UUID shipmentId, String status) {}

  private final JdbcTemplate jdbc;
  private final OutboxWriter outbox;

  ShippingService(JdbcTemplate jdbc, OutboxWriter outbox) {
    this.jdbc = jdbc;
    this.outbox = outbox;
  }

  public void schedule(ScheduleShipment command) {
    UUID orderId = command.orderId();
    Shipment existing = find(orderId);
    if (existing != null && SCHEDULED.equals(existing.status())) {
      reply(orderId, new ShipmentScheduled(orderId, existing.shipmentId()));
    } else if (existing != null) {
      reply(orderId, new ShipmentRejected(orderId, ShipmentRejected.Reason.ALREADY_CANCELLED));
    } else if (book(command)) {
      UUID shipmentId = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO shipment (order_id, shipment_id, delivery_date, status) VALUES (?, ?, ?, ?)",
          orderId,
          shipmentId,
          command.deliveryDate(),
          SCHEDULED);
      reply(orderId, new ShipmentScheduled(orderId, shipmentId));
    } else {
      reply(orderId, new ShipmentRejected(orderId, ShipmentRejected.Reason.NO_CAPACITY));
    }
  }

  public void cancel(CancelShipment command) {
    UUID orderId = command.orderId();
    Shipment existing = find(orderId);
    if (existing != null && SCHEDULED.equals(existing.status())) {
      jdbc.update(
          """
          UPDATE shipping_capacity c SET booked = c.booked - 1
          FROM shipment s
          WHERE s.order_id = ? AND s.delivery_date = c.delivery_date
          """,
          orderId);
      jdbc.update(
          "UPDATE shipment SET status = ?, updated_at = now() WHERE order_id = ?",
          CANCELLED,
          orderId);
    } else if (existing == null) {
      // Lápida: un ScheduleShipment que llegue después no debe agendar.
      jdbc.update("INSERT INTO shipment (order_id, status) VALUES (?, ?)", orderId, CANCELLED);
    }
    reply(orderId, new ShipmentCancelled(orderId));
  }

  private boolean book(ScheduleShipment command) {
    return jdbc.update(
            "UPDATE shipping_capacity SET booked = booked + 1"
                + " WHERE delivery_date = ? AND booked < max_orders",
            command.deliveryDate())
        == 1;
  }

  private Shipment find(UUID orderId) {
    List<Shipment> rows =
        jdbc.query(
            "SELECT shipment_id, status FROM shipment WHERE order_id = ?",
            (rs, n) -> new Shipment(rs.getObject("shipment_id", UUID.class), rs.getString("status")),
            orderId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void reply(UUID orderId, Object message) {
    outbox.enqueueKafka(Topics.SHIPPING_REPLIES, orderId.toString(), message);
  }
}
