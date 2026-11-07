package com.orderflow.order;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** catalog-service y el token endpoint de Keycloak son HTTP externo: WireMock (CLAUDE.md). */
@SpringBootTest(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext
public abstract class AbstractIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15-alpine");

  @Container
  @ServiceConnection
  static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.4.0");

  protected static final WireMockServer wiremock =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    wiremock.start();
  }

  @DynamicPropertySource
  static void externalHttp(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.cloud.discovery.client.simple.instances.catalog-service[0].uri",
        wiremock::baseUrl);
    registry.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri",
        () -> wiremock.baseUrl() + "/token");
  }

  protected static String kafkaBootstrapServers() {
    return kafka.getBootstrapServers();
  }
}
