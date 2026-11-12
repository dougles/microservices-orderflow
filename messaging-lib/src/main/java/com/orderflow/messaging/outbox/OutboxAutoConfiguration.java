package com.orderflow.messaging.outbox;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

  @Bean
  OutboxTracing outboxTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    return new OutboxTracing(
        tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP));
  }

  @Bean
  OutboxWriter outboxWriter(JdbcTemplate jdbc, OutboxTracing tracing) {
    return new OutboxWriter(jdbc, tracing);
  }

  @Bean
  KafkaOutboxSender kafkaOutboxSender(KafkaTemplate<String, String> kafkaTemplate) {
    return new KafkaOutboxSender(kafkaTemplate);
  }

  @Bean
  OutboxPublisher outboxPublisher(
      JdbcTemplate jdbc,
      OutboxTracing tracing,
      OutboxProperties properties,
      List<OutboxSender> senders) {
    return new OutboxPublisher(jdbc, tracing, properties, senders);
  }

  @Bean
  OutboxMetrics outboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
    return new OutboxMetrics(jdbc, registry);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(SqsTemplate.class)
  static class SqsSenderConfiguration {

    @Bean
    SqsOutboxSender sqsOutboxSender(SqsTemplate sqsTemplate) {
      return new SqsOutboxSender(sqsTemplate);
    }
  }
}
