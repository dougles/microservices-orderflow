package com.orderflow.catalogimportfn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CSV de catálogo (SPEC §11): UTF-8, coma, sin comillas, encabezado {@code sku,name,basePrice},
 * hasta 10 000 filas. Reglas de producto de SPEC §9. La línea 1 es el encabezado.
 */
final class CatalogCsv {

  static final String HEADER = "sku,name,basePrice";
  static final int MAX_ROWS = 10_000;
  static final String INCOMING = "incoming/";

  private static final Pattern SKU = Pattern.compile("^[A-Z0-9-]{3,32}$");
  private static final Pattern DECIMAL = Pattern.compile("^-?\\d+(\\.\\d+)?$");
  /** {@code base_price NUMERIC(12,2)}: hasta 10 dígitos enteros. */
  private static final int MAX_INTEGER_DIGITS = 10;

  record Row(String sku, String name, BigDecimal basePrice, int line) {}

  record Error(int line, String reason) {}

  record Result(List<Row> valid, List<Error> errors) {}

  private CatalogCsv() {}

  static Result parse(String content) {
    List<String> lines = lines(content);
    if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) {
      return rejected(1, "INVALID_HEADER");
    }
    if (lines.size() - 1 > MAX_ROWS) {
      return rejected(0, "TOO_MANY_ROWS");
    }
    var valid = new ArrayList<Row>();
    var errors = new ArrayList<Error>();
    var seen = new HashSet<String>();
    for (int i = 1; i < lines.size(); i++) {
      int lineNumber = i + 1;
      String[] fields = lines.get(i).split(",", -1);
      String reason = validate(fields);
      if (reason == null && !seen.add(fields[0])) {
        reason = "DUPLICATE_SKU_IN_FILE";
      }
      if (reason != null) {
        errors.add(new Error(lineNumber, reason));
      } else {
        valid.add(new Row(fields[0], fields[1], new BigDecimal(fields[2]), lineNumber));
      }
    }
    return new Result(List.copyOf(valid), List.copyOf(errors));
  }

  /** Una razón por fila: la del primer campo inválido, en orden sku, name, basePrice. */
  private static String validate(String[] fields) {
    if (!SKU.matcher(fields[0]).matches()) {
      return "INVALID_SKU";
    }
    // Con más de 3 campos, el nombre traía comas.
    if (fields.length < 2 || fields.length > 3 || fields[1].isEmpty() || fields[1].length() > 200) {
      return "INVALID_NAME";
    }
    if (fields.length < 3 || !validPrice(fields[2])) {
      return "INVALID_PRICE";
    }
    return null;
  }

  private static boolean validPrice(String text) {
    if (!DECIMAL.matcher(text).matches()) {
      return false;
    }
    BigDecimal price = new BigDecimal(text);
    BigDecimal normalized = price.stripTrailingZeros();
    return price.signum() > 0
        && Math.max(normalized.scale(), 0) <= 2
        && normalized.precision() - normalized.scale() <= MAX_INTEGER_DIGITS;
  }

  static String errorsCsv(List<Error> errors) {
    var csv = new StringBuilder("line,reason\n");
    for (Error error : errors) {
      csv.append(error.line()).append(',').append(error.reason()).append('\n');
    }
    return csv.toString();
  }

  /** {@code incoming/f15-x.csv} → {@code errors/f15-x.csv.errors.csv}. */
  static String errorsKey(String sourceKey) {
    String name =
        sourceKey.startsWith(INCOMING) ? sourceKey.substring(INCOMING.length()) : sourceKey;
    return "errors/" + name + ".errors.csv";
  }

  private static Result rejected(int line, String reason) {
    return new Result(List.of(), List.of(new Error(line, reason)));
  }

  /** Sin BOM ni {@code \r}; el salto de línea final no cuenta como fila. */
  private static List<String> lines(String content) {
    String text = content.startsWith("﻿") ? content.substring(1) : content;
    var lines = new ArrayList<String>();
    for (String line : text.split("\n", -1)) {
      lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
    }
    if (!lines.isEmpty() && lines.getLast().isEmpty()) {
      lines.removeLast();
    }
    return lines;
  }
}
