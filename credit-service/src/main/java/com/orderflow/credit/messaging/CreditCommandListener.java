package com.orderflow.credit.messaging;

import com.orderflow.contracts.ReleaseCredit;
import com.orderflow.contracts.ReserveCredit;
import com.orderflow.contracts.Topics;
import com.orderflow.credit.domain.CreditService;
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
public class CreditCommandListener {

  private static final String CONSUMER = "credit-service";
  private static final JsonMapper JSON = JsonMapper.shared();

  private final InboxRepository inbox;
  private final CreditService credit;

  CreditCommandListener(InboxRepository inbox, CreditService credit) {
    this.inbox = inbox;
    this.credit = credit;
  }

  @KafkaListener(topics = Topics.CREDIT_COMMANDS, groupId = CONSUMER, concurrency = "3")
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
        case "ReserveCredit" -> credit.reserve(JSON.readValue(record.value(), ReserveCredit.class));
        case "ReleaseCredit" -> credit.release(JSON.readValue(record.value(), ReleaseCredit.class));
        default -> throw new NonRetryableMessageException("Tipo desconocido: " + type);
      }
    }
  }
}
