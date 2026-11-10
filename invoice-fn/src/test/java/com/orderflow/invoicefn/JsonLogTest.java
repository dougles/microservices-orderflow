package com.orderflow.invoicefn;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonLogTest {

  @Test
  void elTraceIdEsElSegundoCampoDelTraceparent() {
    assertThat(JsonLog.traceId("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
        .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
  }

  @Test
  void sinTraceparentValidoNoHayTraceId() {
    assertThat(JsonLog.traceId(null)).isNull();
    assertThat(JsonLog.traceId("")).isNull();
    assertThat(JsonLog.traceId("basura")).isNull();
  }

  @Test
  void cadaEventoEsUnaLineaJsonConNivelYSinCamposNulos() throws Exception {
    var fields = new HashMap<String, String>();
    fields.put("orderId", "o-1");
    fields.put("traceId", null);

    String line = JsonLog.line("INFO", "factura generada", fields);

    assertThat(line).doesNotContain("\n");
    Map<String, Object> event =
        new ObjectMapper().readValue(line, new TypeReference<Map<String, Object>>() {});
    assertThat(event)
        .containsEntry("level", "INFO")
        .containsEntry("orderId", "o-1")
        .containsEntry("message", "factura generada")
        .doesNotContainKey("traceId");
  }
}
