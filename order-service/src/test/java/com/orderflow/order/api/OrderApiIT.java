package com.orderflow.order.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.orderflow.contracts.Topics;
import com.orderflow.order.AbstractIT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

class OrderApiIT extends AbstractIT {

  // ---------- POST ----------

  @Test
  void creaElPedidoEnAwaitingStockYEmiteReserveStock() throws Exception {
    stubPrices("SKU-0001", "9.00", "SKU-0002", "18.00");
    Instant before = Instant.now();

    String body =
        createOrder("ACME", UUID.randomUUID(), order(TODAY.plusDays(7), "SKU-0001", 5, "SKU-0002", 3))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID orderId = UUID.fromString(JSON.readTree(body).get("orderId").asString());

    mvc.perform(get(ORDERS + "/" + orderId).with(buyer("ACME")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value(orderId.toString()))
        .andExpect(jsonPath("$.companyId").value("ACME"))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.reason").isEmpty())
        .andExpect(jsonPath("$.deliveryDate").value(TODAY.plusDays(7).toString()))
        .andExpect(jsonPath("$.currency").value("BOB"))
        .andExpect(jsonPath("$.total").value("99.00"))
        .andExpect(jsonPath("$.items[0].sku").value("SKU-0001"))
        .andExpect(jsonPath("$.items[0].quantity").value(5))
        .andExpect(jsonPath("$.items[0].unitPrice").value("9.00"))
        .andExpect(jsonPath("$.items[1].sku").value("SKU-0002"))
        .andExpect(jsonPath("$.items[1].unitPrice").value("18.00"))
        .andExpect(jsonPath("$.createdAt").isNotEmpty())
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());

    Map<String, Object> saga =
        jdbc.queryForMap("SELECT state, step_deadline FROM saga WHERE order_id = ?", orderId);
    assertThat(saga.get("state")).isEqualTo("AWAITING_STOCK");
    Instant deadline = ((java.sql.Timestamp) saga.get("step_deadline")).toInstant();
    assertThat(deadline).isBetween(before.plus(Duration.ofMinutes(5)), Instant.now().plus(Duration.ofMinutes(5)));

    // Precios pedidos con la empresa del token y el token client credentials (SPEC §6).
    wiremock.verify(
        getRequestedFor(urlPathEqualTo(PRICES))
            .withQueryParam("companyId", equalTo("ACME"))
            .withQueryParam("skus", equalTo("SKU-0001,SKU-0002"))
            .withHeader("Authorization", equalTo("Bearer catalog-token")));

    ConsumerRecord<String, String> command = awaitCommand(orderId);
    assertThat(header(command, "message_type")).isEqualTo("ReserveStock");
    JsonNode payload = JSON.readTree(command.value());
    assertThat(payload.get("orderId").asString()).isEqualTo(orderId.toString());
    assertThat(payload.get("items")).hasSize(2);
    assertThat(payload.get("items").get(0).get("sku").asString()).isEqualTo("SKU-0001");
    assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_message WHERE published_at IS NOT NULL", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void respondeConLocationDelPedido() throws Exception {
    stubPrices("SKU-0001", "9.00");
    var response =
        createOrder("ACME", UUID.randomUUID(), order(TODAY.plusDays(3), "SKU-0001", 1))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse();
    String orderId = JSON.readTree(response.getContentAsString()).get("orderId").asString();
    assertThat(response.getHeader("Location")).isEqualTo("/api/orders/" + orderId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "no-es-uuid", "1-1-1-1-1"})
  void sinIdempotencyKeyValidaResponde400(String key) throws Exception {
    var request =
        post(ORDERS)
            .with(buyer("ACME"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(order(TODAY.plusDays(3), "SKU-0001", 1));
    if (!key.isEmpty()) {
      request.header("Idempotency-Key", key);
    }
    mvc.perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
  }

  static Stream<String> invalidBodies() {
    var items51 = new ArrayList<String>();
    for (int i = 1; i <= 51; i++) {
      items51.add("{\"sku\":\"SKU-%04d\",\"quantity\":1}".formatted(i));
    }
    String ok = TODAY.plusDays(3).toString();
    return Stream.of(
        "{\"deliveryDate\":\"%s\",\"items\":[]}".formatted(ok),
        "{\"deliveryDate\":\"%s\",\"items\":[%s]}".formatted(ok, String.join(",", items51)),
        "{\"deliveryDate\":\"%s\",\"items\":[{\"sku\":\"SKU-0001\",\"quantity\":1},{\"sku\":\"SKU-0001\",\"quantity\":2}]}"
            .formatted(ok),
        "{\"deliveryDate\":\"%s\",\"items\":[{\"sku\":\"SKU-0001\",\"quantity\":0}]}".formatted(ok),
        "{\"deliveryDate\":\"%s\",\"items\":[{\"sku\":\"SKU-0001\",\"quantity\":10001}]}"
            .formatted(ok),
        "{\"deliveryDate\":\"%s\",\"items\":[{\"sku\":\"SKU-0001\",\"quantity\":1}]}"
            .formatted(TODAY),
        "{\"deliveryDate\":\"%s\",\"items\":[{\"sku\":\"SKU-0001\",\"quantity\":1}]}"
            .formatted(TODAY.plusDays(61)),
        "{\"items\":[{\"sku\":\"SKU-0001\",\"quantity\":1}]}",
        "{\"deliveryDate\":\"%s\",\"items\":[{\"quantity\":1}]}".formatted(ok),
        "{\"deliveryDate\":\"no-es-fecha\",\"items\":[{\"sku\":\"SKU-0001\",\"quantity\":1}]}",
        "no es json");
  }

  @ParameterizedTest
  @MethodSource("invalidBodies")
  void bodyInvalidoResponde400SinLlamarACatalog(String body) throws Exception {
    createOrder("ACME", UUID.randomUUID(), body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    assertThat(wiremock.findAll(getRequestedFor(urlPathEqualTo(PRICES)))).isEmpty();
    assertThat(count("orders")).isZero();
  }

  @Test
  void losLimitesDelRangoSonValidos() throws Exception {
    stubPrices("SKU-0001", "9.00");
    createOrder("ACME", UUID.randomUUID(), order(TODAY.plusDays(1), "SKU-0001", 10_000))
        .andExpect(status().isAccepted());
    createOrder("ACME", UUID.randomUUID(), order(TODAY.plusDays(60), "SKU-0001", 1))
        .andExpect(status().isAccepted());
  }

  @Test
  void skuDesconocidoResponde422YNoGuardaNada() throws Exception {
    wiremock.stubFor(
        WireMock.get(urlPathEqualTo(PRICES))
            .willReturn(
                okJson(
                    """
                    {"currency":"BOB","items":[{"sku":"SKU-0001","unitPrice":"9.00"}],"unknownSkus":["SKU-9999"]}
                    """)));
    createOrder("ACME", UUID.randomUUID(), order(TODAY.plusDays(3), "SKU-0001", 1, "SKU-9999", 1))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("UNKNOWN_SKU"));
    assertThat(count("orders")).isZero();
    assertThat(count("idempotency_key")).isZero();
    assertThat(count("outbox_message")).isZero();
  }

  @Test
  void catalogConErrorResponde503YLaKeyNoSeGuarda() throws Exception {
    wiremock.stubFor(WireMock.get(urlPathEqualTo(PRICES)).willReturn(aResponse().withStatus(500)));
    UUID key = UUID.randomUUID();
    String body = order(TODAY.plusDays(3), "SKU-0001", 1);

    createOrder("ACME", key, body)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));
    assertThat(count("idempotency_key")).isZero();
    assertThat(count("orders")).isZero();

    stubPrices("SKU-0001", "9.00");
    createOrder("ACME", key, body).andExpect(status().isAccepted());
    assertThat(count("orders")).isEqualTo(1);
  }

  @Test
  void catalogLentoCortaPorReadTimeout() throws Exception {
    wiremock.stubFor(
        WireMock.get(urlPathEqualTo(PRICES))
            .willReturn(
                okJson(
                        """
                        {"currency":"BOB","items":[{"sku":"SKU-0001","unitPrice":"9.00"}],"unknownSkus":[]}
                        """)
                    .withFixedDelay(4_000)));
    long start = System.nanoTime();
    createOrder("ACME", UUID.randomUUID(), order(TODAY.plusDays(3), "SKU-0001", 1))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
  }

  @Test
  void elCircuitoSeAbreTras10FallosYNoLlamaACatalog() throws Exception {
    wiremock.stubFor(WireMock.get(urlPathEqualTo(PRICES)).willReturn(aResponse().withStatus(500)));
    String body = order(TODAY.plusDays(3), "SKU-0001", 1);
    for (int i = 0; i < 10; i++) {
      createOrder("ACME", UUID.randomUUID(), body).andExpect(status().isServiceUnavailable());
    }
    int calls = wiremock.findAll(getRequestedFor(urlPathEqualTo(PRICES))).size();
    assertThat(calls).isEqualTo(10);

    stubPrices("SKU-0001", "9.00");
    createOrder("ACME", UUID.randomUUID(), body)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));
    assertThat(wiremock.findAll(getRequestedFor(urlPathEqualTo(PRICES)))).hasSize(calls);
  }

  // ---------- GET ----------

  @Test
  void listaOrdenadoPorCreacionDescendenteYPaginado() throws Exception {
    stubPrices("SKU-0001", "9.00");
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ids.add(createdOrderId("ACME", order(TODAY.plusDays(3 + i), "SKU-0001", 1)));
    }

    mvc.perform(get(ORDERS).param("size", "2").with(buyer("ACME")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].orderId").value(ids.get(2)))
        .andExpect(jsonPath("$.content[1].orderId").value(ids.get(1)))
        .andExpect(jsonPath("$.content[0].items[0].unitPrice").value("9.00"));

    mvc.perform(get(ORDERS).param("page", "1").param("size", "2").with(buyer("ACME")))
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].orderId").value(ids.get(0)));
  }

  @Test
  void filtraPorEstadoPublico() throws Exception {
    stubPrices("SKU-0001", "9.00");
    String pending = createdOrderId("ACME", order(TODAY.plusDays(3), "SKU-0001", 1));
    String rejected = createdOrderId("ACME", order(TODAY.plusDays(4), "SKU-0001", 1));
    String cancelled = createdOrderId("ACME", order(TODAY.plusDays(5), "SKU-0001", 1));
    setSaga(rejected, "REJECTED", "OUT_OF_STOCK");
    setSaga(cancelled, "COMPENSATION_FAILED", "STEP_TIMEOUT");

    expectOnly("PENDING", pending);
    expectOnly("REJECTED", rejected);
    expectOnly("CANCELLED", cancelled);
    mvc.perform(get(ORDERS).param("status", "CONFIRMED").with(buyer("ACME")))
        .andExpect(jsonPath("$.totalElements").value(0));

    mvc.perform(get(ORDERS + "/" + cancelled).with(buyer("ACME")))
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.reason").value("STEP_TIMEOUT"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"size=101", "size=0", "page=-1", "status=OTRO"})
  void parametrosDeListadoInvalidosResponden400(String param) throws Exception {
    String[] kv = param.split("=");
    mvc.perform(get(ORDERS).param(kv[0], kv[1]).with(buyer("ACME")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void pedidoInexistenteResponde404() throws Exception {
    mvc.perform(get(ORDERS + "/" + UUID.randomUUID()).with(buyer("ACME")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // ---------- contexto ----------

  /**
   * Eureka toma el {@code RestClient.Builder} del contexto con {@code getIfAvailable}: si viera el
   * builder con LoadBalancer, resolvería {@code discovery-server} como servicio y no se registraría.
   */
  @Test
  void elBuilderConLoadBalancerNoQuedaDisponibleParaEureka(
      @Autowired ApplicationContext context) {
    RestClient.Builder available = context.getBeanProvider(RestClient.Builder.class).getIfAvailable();
    if (available != null) {
      var interceptors = new ArrayList<String>();
      available
          .clone()
          .requestInterceptors(list -> list.forEach(i -> interceptors.add(i.getClass().getName())));
      assertThat(interceptors).noneMatch(name -> name.contains("LoadBalancer"));
    }
  }

  // ---------- seguridad ----------

  @Test
  void sinTokenResponde401() throws Exception {
    mvc.perform(get(ORDERS)).andExpect(status().isUnauthorized());
  }

  @Test
  void opsNoPuedeCrearPedidos() throws Exception {
    mvc.perform(
            post(ORDERS)
                .with(ops())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(order(TODAY.plusDays(3), "SKU-0001", 1)))
        .andExpect(status().isForbidden());
  }

  @Test
  void compradorSinCompanyIdNoPuedeOperar() throws Exception {
    var noCompany = jwt().authorities(new SimpleGrantedAuthority("ROLE_buyer"));
    mvc.perform(
            post(ORDERS)
                .with(noCompany)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(order(TODAY.plusDays(3), "SKU-0001", 1)))
        .andExpect(status().isForbidden());
    mvc.perform(get(ORDERS).with(noCompany)).andExpect(status().isForbidden());
  }

  // ---------- helpers ----------

  private void expectOnly(String status, String orderId) throws Exception {
    mvc.perform(get(ORDERS).param("status", status).with(buyer("ACME")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].orderId").value(orderId))
        .andExpect(jsonPath("$.content[0].status").value(status));
  }

  private void setSaga(String orderId, String state, String reason) {
    jdbc.update(
        "UPDATE saga SET state = ?, failure_reason = ?, step_deadline = NULL WHERE order_id = ?",
        state,
        reason,
        UUID.fromString(orderId));
  }

  private static ConsumerRecord<String, String> awaitCommand(UUID orderId) {
    var props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    try (var consumer = new KafkaConsumer<String, String>(props)) {
      consumer.subscribe(List.of(Topics.INVENTORY_COMMANDS));
      var found = new ArrayList<ConsumerRecord<String, String>>();
      await()
          .atMost(Duration.ofSeconds(15))
          .until(
              () -> {
                consumer
                    .poll(Duration.ofMillis(200))
                    .forEach(
                        r -> {
                          if (orderId.toString().equals(r.key())) {
                            found.add(r);
                          }
                        });
                return !found.isEmpty();
              });
      return found.getFirst();
    }
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
