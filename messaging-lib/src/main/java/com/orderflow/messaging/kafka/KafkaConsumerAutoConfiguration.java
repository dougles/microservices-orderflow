package com.orderflow.messaging.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(InboxProperties.class)
public class KafkaConsumerAutoConfiguration {

  private static final int RETRIES = 3;
  private static final long INITIAL_BACKOFF_MILLIS = 1_000;
  private static final double BACKOFF_MULTIPLIER = 2.0;

  @Bean
  InboxRepository inboxRepository(JdbcTemplate jdbc, InboxProperties properties) {
    return new InboxRepository(jdbc, properties);
  }

  @Bean
  DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
    var recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (record, exception) ->
                new TopicPartition(record.topic() + ".DLT", record.partition()));
    var backOff = new ExponentialBackOffWithMaxRetries(RETRIES);
    backOff.setInitialInterval(INITIAL_BACKOFF_MILLIS);
    backOff.setMultiplier(BACKOFF_MULTIPLIER);
    var handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(
        NonRetryableMessageException.class, JacksonException.class);
    return handler;
  }

  @Bean
  DltReplayer dltReplayer(
      ConsumerFactory<String, String> consumerFactory,
      KafkaTemplate<String, String> template,
      KafkaListenerEndpointRegistry listeners,
      @Value("${spring.application.name}") String applicationName) {
    return new DltReplayer(
        consumerFactory, template.getProducerFactory(), listeners, applicationName);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(RestController.class)
  static class ReplayEndpointConfiguration {

    @Bean
    DltReplayController dltReplayController(DltReplayer replayer) {
      return new DltReplayController(replayer);
    }
  }
}
