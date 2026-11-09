package com.orderflow.inventory.messaging;

import com.orderflow.contracts.ReleaseStock;
import com.orderflow.contracts.ReserveStock;
import com.orderflow.contracts.Topics;
import com.orderflow.inventory.domain.InventoryService;
import com.orderflow.messaging.kafka.InboxRepository;
import com.orderflow.messaging.kafka.KafkaHeaders;
import com.orderflow.messaging.kafka.NonRetryableMessageException;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class InventoryCommandListener {

  private static final String CONSUMER = "inventory-service";
  private static final JsonMapper JSON = JsonMapper.shared();

  private final InboxRepository inbox;
  private final InventoryService inventory;

  InventoryCommandListener(InboxRepository inbox, InventoryService inventory) {
    this.inbox = inbox;
    this.inventory = inventory;
  }

  @KafkaListener(topics = Topics.INVENTORY_COMMANDS, groupId = CONSUMER, concurrency = "3")
  @Transactional
  public void onMessage(ConsumerRecord<String, String> record) {
    UUID messageId = KafkaHeaders.messageId(record);
    try (var ignored = MDC.putCloseable("messageId", messageId.toString());
        var ignored2 = MDC.putCloseable("orderId", record.key())) {
      if (!inbox.firstTime(messageId, CONSUMER)) {
        return;
      }
      String type = KafkaHeaders.messageType(record);
      switch (type) {
        case "ReserveStock" -> inventory.reserve(JSON.readValue(record.value(), ReserveStock.class));
        case "ReleaseStock" -> inventory.release(JSON.readValue(record.value(), ReleaseStock.class));
        default -> throw new NonRetryableMessageException("Tipo desconocido: " + type);
      }
    }
  }
}
