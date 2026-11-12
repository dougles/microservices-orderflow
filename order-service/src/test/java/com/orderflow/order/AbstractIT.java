package com.orderflow.order;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.orderflow.order.config.CatalogClientConfig;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** catalog-service y el token endpoint de Keycloak son HTTP externo: WireMock (CLAUDE.md). */
@SpringBootTest(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext
public abstract class AbstractIT {

  protected static final String ORDERS = "/api/orders";
  protected static final String PRICES = "/internal/prices";
  protected static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/La_Paz"));
  protected static final JsonMapper JSON = JsonMapper.shared();

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

  @Autowired protected MockMvc mvc;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired Resilience4JCircuitBreakerFactory circuitBreakers;

  @DynamicPropertySource
  static void externalHttp(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.cloud.discovery.client.simple.instances.catalog-service[0].uri",
        wiremock::baseUrl);
    registry.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri",
        () -> wiremock.baseUrl() + "/token");
  }

  @BeforeEach
  void stubToken() {
    wiremock.stubFor(
        WireMock.post(urlPathEqualTo("/token"))
            .willReturn(
                okJson(
                    """
                    {"access_token":"catalog-token","token_type":"Bearer","expires_in":300}
                    """)));
  }

  @AfterEach
  void resetState() {
    wiremock.resetAll();
    // find, no circuitBreaker(name): este último lo crearía con la config por defecto.
    circuitBreakers
        .getCircuitBreakerRegistry()
        .find(CatalogClientConfig.CIRCUIT_BREAKER)
        .ifPresent(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
    jdbc.update("DELETE FROM outbox_message");
    jdbc.update("DELETE FROM idempotency_key");
    jdbc.update("DELETE FROM saga");
    jdbc.update("DELETE FROM order_line");
    jdbc.update("DELETE FROM orders");
  }

  protected static String kafkaBootstrapServers() {
    return kafka.getBootstrapServers();
  }

  protected ResultActions createOrder(String companyId, UUID key, String body) throws Exception {
    return mvc.perform(
        post(ORDERS)
            .with(buyer(companyId))
            .header("Idempotency-Key", key.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** POST con key nueva; exige 202 y devuelve el orderId. */
  protected String createdOrderId(String companyId, String body) throws Exception {
    String response =
        createOrder(companyId, UUID.randomUUID(), body)
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(response).get("orderId").asString();
  }

  protected int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  /** Pares sku, precio. */
  protected static void stubPrices(String... skuPrice) {
    var items = new ArrayList<String>();
    for (int i = 0; i < skuPrice.length; i += 2) {
      items.add("{\"sku\":\"%s\",\"unitPrice\":\"%s\"}".formatted(skuPrice[i], skuPrice[i + 1]));
    }
    wiremock.stubFor(
        WireMock.get(urlPathEqualTo(PRICES))
            .willReturn(
                okJson(
                    "{\"currency\":\"BOB\",\"items\":[%s],\"unknownSkus\":[]}"
                        .formatted(String.join(",", items)))));
  }

  /** Pares sku, cantidad. */
  protected static String order(LocalDate deliveryDate, Object... skuQuantity) {
    var items = new ArrayList<String>();
    for (int i = 0; i < skuQuantity.length; i += 2) {
      items.add("{\"sku\":\"%s\",\"quantity\":%s}".formatted(skuQuantity[i], skuQuantity[i + 1]));
    }
    return "{\"deliveryDate\":\"%s\",\"items\":[%s]}"
        .formatted(deliveryDate, String.join(",", items));
  }

  protected static JwtRequestPostProcessor buyer(String companyId) {
    return jwt()
        .jwt(j -> j.claim("company_id", companyId))
        .authorities(new SimpleGrantedAuthority("ROLE_buyer"));
  }

  protected static JwtRequestPostProcessor ops() {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_ops"));
  }
}
