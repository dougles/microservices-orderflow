package com.orderflow.messaging.outbox;

import java.util.UUID;

record OutboxRow(
    UUID id,
    String destinationType,
    String destination,
    String messageKey,
    String messageType,
    String payload,
    String traceparent) {}
