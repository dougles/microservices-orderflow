package com.orderflow.order.messaging;

import com.orderflow.contracts.CreditRejected;
import com.orderflow.contracts.CreditReleased;
import com.orderflow.contracts.CreditReserved;
import com.orderflow.contracts.ShipmentCancelled;
import com.orderflow.contracts.ShipmentRejected;
import com.orderflow.contracts.ShipmentScheduled;
import com.orderflow.contracts.StockRejected;
import com.orderflow.contracts.StockReleased;
import com.orderflow.contracts.StockReserved;
import com.orderflow.contracts.Topics;
import com.orderflow.messaging.kafka.InboxRepository;
import com.orderflow.messaging.kafka.KafkaHeaders;
import com.orderflow.messaging.kafka.NonRetryableMessageException;
import com.orderflow.order.domain.SagaOrchestrator;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SagaReplyListener {

  private static final String CONSUMER = "order-service";
  private static final JsonMapper JSON = JsonMapper.shared();

  private final InboxRepository inbox;
  private final SagaOrchestrator orchestrator;

  SagaReplyListener(InboxRepository inbox, SagaOrchestrator orchestrator) {
    this.inbox = inbox;
    this.orchestrator = orchestrator;
  }

  @KafkaListener(
      topics = {Topics.INVENTORY_REPLIES, Topics.CREDIT_REPLIES, Topics.SHIPPING_REPLIES},
      groupId = CONSUMER,
      concurrency = "3")
  @Transactional
  public void onMessage(ConsumerRecord<String, String> record) {
    UUID messageId = KafkaHeaders.messageId(record);
    try (var ignored = MDC.putCloseable("messageId", messageId.toString());
        var ignored2 = MDC.putCloseable("orderId", record.key())) {
      if (!inbox.firstTime(messageId, CONSUMER)) {
        return;
      }
      dispatch(KafkaHeaders.messageType(record), record.value());
    }
  }

  private void dispatch(String type, String value) {
    switch (type) {
      case "StockReserved" ->
          orchestrator.onStockReserved(read(value, StockReserved.class).orderId());
      case "StockRejected" ->
          orchestrator.onStockRejected(read(value, StockRejected.class).orderId());
      case "StockReleased" ->
          orchestrator.onStockReleased(read(value, StockReleased.class).orderId());
      case "CreditReserved" ->
          orchestrator.onCreditReserved(read(value, CreditReserved.class).orderId());
      case "CreditRejected" -> {
        CreditRejected rejected = read(value, CreditRejected.class);
        orchestrator.onCreditRejected(rejected.orderId(), rejected.reason());
      }
      case "CreditReleased" ->
          orchestrator.onCreditReleased(read(value, CreditReleased.class).orderId());
      case "ShipmentScheduled" ->
          orchestrator.onShipmentScheduled(read(value, ShipmentScheduled.class).orderId());
      case "ShipmentRejected" ->
          orchestrator.onShipmentRejected(read(value, ShipmentRejected.class).orderId());
      case "ShipmentCancelled" ->
          orchestrator.onShipmentCancelled(read(value, ShipmentCancelled.class).orderId());
      default -> throw new NonRetryableMessageException("Tipo desconocido: " + type);
    }
  }

  private static <T> T read(String value, Class<T> type) {
    return JSON.readValue(value, type);
  }
}
