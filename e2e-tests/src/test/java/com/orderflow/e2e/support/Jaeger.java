package com.orderflow.e2e.support;

import static io.restassured.RestAssured.given;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** API de consulta de Jaeger en {@code http://localhost:16686}. */
public final class Jaeger {

  private static final String BASE_URL = "http://localhost:16686";

  /** Una traza y los servicios que aportaron spans a ella. */
  public record Trace(String traceId, Set<String> services) {}

  private Jaeger() {}

  /** Trazas de {@code service} que tienen un span con el atributo {@code tag = value}. */
  public static List<Trace> tracesByTag(String service, String tag, String value) {
    List<Map<String, Object>> traces =
        given()
            .baseUri(BASE_URL)
            .queryParam("service", service)
            .queryParam("tags", "{\"%s\":\"%s\"}".formatted(tag, value))
            .queryParam("lookback", "1h")
            .queryParam("limit", 20)
            .get("/api/traces")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("data");
    return traces.stream().map(Jaeger::trace).toList();
  }

  @SuppressWarnings("unchecked")
  private static Trace trace(Map<String, Object> trace) {
    var processes = (Map<String, Map<String, Object>>) trace.get("processes");
    var spans = (List<Map<String, Object>>) trace.get("spans");
    var services = new HashSet<String>();
    for (Map<String, Object> span : spans) {
      Map<String, Object> process = processes.get((String) span.get("processID"));
      if (process != null) {
        services.add((String) process.get("serviceName"));
      }
    }
    return new Trace((String) trace.get("traceID"), Set.copyOf(services));
  }
}
