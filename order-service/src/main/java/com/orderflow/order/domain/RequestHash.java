package com.orderflow.order.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * SHA-256 del body normalizado (SPEC §10): fecha e ítems ordenados por sku, así el orden de los
 * ítems o el formato del JSON no cambian el hash.
 */
public final class RequestHash {

  private RequestHash() {}

  public static String of(NewOrder order) {
    String items =
        order.items().stream()
            .sorted(Comparator.comparing(NewOrder.Item::sku))
            .map(item -> item.sku() + ":" + item.quantity())
            .collect(Collectors.joining(","));
    String normalized = "deliveryDate=" + order.deliveryDate() + ";items=" + items;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
