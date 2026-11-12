package com.orderflow.messaging.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orderflow.inbox")
public record InboxProperties(Duration retention) {}
