package com.orderflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * F02 — Misma key, body distinto. POST con key K; POST con key K y otra quantity. El 2.º responde
 * 422 IDEMPOTENCY_KEY_REUSED; el pedido original no cambia.
 */
class F02KeyReusedWithOtherBodyIT extends AbstractIT {

  @Test
  void laMismaKeyConOtraCantidadResponde422YNoTocaElPedido() throws Exception {
    stubPrices("SKU-0001", "9.00");
    UUID key = UUID.randomUUID();
    String original = order(TODAY.plusDays(7), "SKU-0001", 2);
    String otherQuantity = order(TODAY.plusDays(7), "SKU-0001", 3);

    String response =
        createOrder("ACME", key, original)
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String orderId = JSON.readTree(response).get("orderId").asString();
    String before = orderJson(orderId);

    createOrder("ACME", key, otherQuantity)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    mvc.perform(get(ORDERS + "/" + orderId).with(buyer("ACME")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].quantity").value(2))
        .andExpect(jsonPath("$.total").value("18.00"));
    assertThat(orderJson(orderId)).isEqualTo(before);
    assertThat(count("orders")).isEqualTo(1);
    assertThat(count("order_line")).isEqualTo(1);
    assertThat(count("outbox_message")).isEqualTo(1);
  }

  private String orderJson(String orderId) throws Exception {
    return mvc.perform(get(ORDERS + "/" + orderId).with(buyer("ACME")))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
