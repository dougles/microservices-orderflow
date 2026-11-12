package com.orderflow.messaging.outbox;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.util.HashMap;

class SqsOutboxSender implements OutboxSender {

  private final SqsTemplate sqsTemplate;

  SqsOutboxSender(SqsTemplate sqsTemplate) {
    this.sqsTemplate = sqsTemplate;
  }

  @Override
  public String destinationType() {
    return OutboxWriter.SQS;
  }

  @Override
  public void send(OutboxRow row) {
    var attributes = new HashMap<String, Object>();
    attributes.put("message_id", row.id().toString());
    attributes.put("message_type", row.messageType());
    if (row.traceparent() != null) {
      attributes.put("traceparent", row.traceparent());
    }
    sqsTemplate.send(to -> to.queue(row.destination()).payload(row.payload()).headers(attributes));
  }
}
