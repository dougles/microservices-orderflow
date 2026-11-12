package com.orderflow.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SupportTest {

  @Test
  void leeLosEstadosEnAmbosFormatosDeComposePs() {
    String lines =
        """
        {"Name":"orderflow-catalog-service-1","State":"paused"}
        {"Name":"orderflow-catalog-service-2","State":"running"}
        """;
    String array = "[{\"Name\":\"a\",\"State\": \"exited\"},{\"Name\":\"b\",\"State\":\"exited\"}]";
    assertThat(Compose.parseStates(lines)).containsExactlyInAnyOrder("paused", "running");
    assertThat(Compose.parseStates(array)).containsExactly("exited");
    assertThat(Compose.parseStates("")).isEmpty();
  }

  @Test
  void armaElBodyDelPedido() {
    assertThat(Api.order(LocalDate.parse("2026-11-20"), "SKU-0001", 2, "SKU-0002", 3))
        .isEqualTo(
            "{\"deliveryDate\":\"2026-11-20\",\"items\":"
                + "[{\"sku\":\"SKU-0001\",\"quantity\":2},{\"sku\":\"SKU-0002\",\"quantity\":3}]}");
    assertThatThrownBy(() -> Api.order("SKU-0001")).isInstanceOf(IllegalArgumentException.class);
  }
}
