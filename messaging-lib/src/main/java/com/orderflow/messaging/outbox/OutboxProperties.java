package com.orderflow.messaging.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orderflow.outbox")
public record OutboxProperties(Duration pollInterval, int batchSize, Duration retention) {}
