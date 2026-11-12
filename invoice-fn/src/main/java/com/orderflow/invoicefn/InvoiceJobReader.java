package com.orderflow.invoicefn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.InvoiceJob;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Lee el body de {@code invoice-jobs}. Lo escribe order-service con Jackson 3; aquí se lee con
 * Jackson 2 sin módulos (SPEC §2), por eso el árbol se recorre a mano.
 */
final class InvoiceJobReader {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  private InvoiceJobReader() {}

  /**
   * @throws IllegalArgumentException si el body no es JSON o no es un InvoiceJob completo
   */
  static InvoiceJob read(String body) {
    JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("body no es JSON", e);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("body no es un objeto JSON");
    }
    JsonNode linesNode = required(root, "lines");
    if (!linesNode.isArray() || linesNode.isEmpty()) {
      throw new IllegalArgumentException("lines debe ser un arreglo no vacío");
    }
    var lines = new ArrayList<InvoiceJob.Line>();
    for (JsonNode line : linesNode) {
      lines.add(
          new InvoiceJob.Line(
              text(line, "sku"), required(line, "quantity").asInt(), decimal(line, "unitPrice")));
    }
    return new InvoiceJob(
        UUID.fromString(text(root, "orderId")),
        text(root, "companyId"),
        lines,
        decimal(root, "total"),
        text(root, "currency"),
        instant(required(root, "confirmedAt")));
  }

  private static JsonNode required(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("falta " + field);
    }
    return value;
  }

  private static String text(JsonNode node, String field) {
    return required(node, field).asText();
  }

  /** Dinero con escala 2 (SPEC §9): el árbol de Jackson normaliza 9.00 a 9. */
  private static BigDecimal decimal(JsonNode node, String field) {
    JsonNode value = required(node, field);
    BigDecimal amount = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
    return amount.setScale(2, RoundingMode.HALF_EVEN);
  }

  /** ISO-8601 (lo que escribe Jackson 3) o segundos epoch con decimales. */
  private static Instant instant(JsonNode value) {
    if (value.isNumber()) {
      BigDecimal seconds = value.decimalValue();
      return Instant.ofEpochSecond(
          seconds.longValue(), seconds.remainder(BigDecimal.ONE).movePointRight(9).longValue());
    }
    return Instant.parse(value.asText());
  }
}
