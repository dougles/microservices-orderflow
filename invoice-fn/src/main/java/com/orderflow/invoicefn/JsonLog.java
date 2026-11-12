package com.orderflow.invoicefn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Una línea JSON por evento a stdout, con {@code traceId} extraído de {@code traceparent}. */
final class JsonLog {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonLog() {}

  /** {@code traceparent} = version-traceId-spanId-flags; null si no tiene ese formato. */
  static String traceId(String traceparent) {
    if (traceparent == null) {
      return null;
    }
    String[] parts = traceparent.split("-");
    return parts.length >= 4 && !parts[1].isBlank() ? parts[1] : null;
  }

  static String line(String level, String message, Map<String, String> fields) {
    var event = new LinkedHashMap<String, Object>();
    event.put("@timestamp", Instant.now().toString());
    event.put("level", level);
    event.put("logger_name", "invoice-fn");
    event.put("message", message);
    fields.forEach(
        (key, value) -> {
          if (value != null) {
            event.put(key, value);
          }
        });
    try {
      return MAPPER.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      return "{\"level\":\"ERROR\",\"message\":\"no se pudo serializar el log\"}";
    }
  }

  static void info(String message, Map<String, String> fields) {
    System.out.println(line("INFO", message, fields));
  }

  static void error(String message, Map<String, String> fields) {
    System.out.println(line("ERROR", message, fields));
  }
}
