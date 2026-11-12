package com.orderflow.shipping.messaging;

import com.orderflow.contracts.CancelShipment;
import com.orderflow.contracts.ScheduleShipment;
import com.orderflow.contracts.Topics;
import com.orderflow.messaging.kafka.InboxRepository;
import com.orderflow.messaging.kafka.KafkaHeaders;
import com.orderflow.messaging.kafka.NonRetryableMessageException;
import com.orderflow.shipping.domain.ShippingService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ShippingCommandListener {

  private static final String CONSUMER = "shipping-service";
  private static final JsonMapper JSON = JsonMapper.shared();

  private final InboxRepository inbox;
  private final ShippingService shipping;

  ShippingCommandListener(InboxRepository inbox, ShippingService shipping) {
    this.inbox = inbox;
    this.shipping = shipping;
  }

  @KafkaListener(topics = Topics.SHIPPING_COMMANDS, groupId = CONSUMER, concurrency = "3")
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
        case "ScheduleShipment" ->
            shipping.schedule(JSON.readValue(record.value(), ScheduleShipment.class));
        case "CancelShipment" ->
            shipping.cancel(JSON.readValue(record.value(), CancelShipment.class));
        default -> throw new NonRetryableMessageException("Tipo desconocido: " + type);
      }
    }
  }
}
