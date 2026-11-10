package com.orderflow.e2e.support;

import static io.restassured.RestAssured.given;

import java.util.List;
import java.util.Map;

/** API de Prometheus en {@code http://localhost:9090}. "Alerta" = estado FIRING (SPEC §13). */
public final class Prometheus {

  private static final String BASE_URL = "http://localhost:9090";

  private Prometheus() {}

  public static boolean alertFiring(String name) {
    List<Map<String, Object>> alerts =
        given()
            .baseUri(BASE_URL)
            .get("/api/v1/alerts")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("data.alerts");
    return alerts.stream()
        .anyMatch(
            alert ->
                alert.get("labels") instanceof Map<?, ?> labels
                    && name.equals(labels.get("alertname"))
                    && "firing".equals(alert.get("state")));
  }
}
