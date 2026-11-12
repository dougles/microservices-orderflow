package com.orderflow.catalogimportfn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Una línea JSON por evento a stdout. Un evento de S3 no trae {@code traceparent}: la correlación
 * es por {@code sourceFile}.
 */
final class JsonLog {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonLog() {}

  static String line(String level, String message, Map<String, ?> fields) {
    var event = new LinkedHashMap<String, Object>();
    event.put("@timestamp", Instant.now().toString());
    event.put("level", level);
    event.put("logger_name", "catalog-import-fn");
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

  static void info(String message, Map<String, ?> fields) {
    System.out.println(line("INFO", message, fields));
  }

  static void error(String message, Map<String, ?> fields) {
    System.out.println(line("ERROR", message, fields));
  }
}
