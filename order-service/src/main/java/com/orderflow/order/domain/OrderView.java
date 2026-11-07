package com.orderflow.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Lectura de un pedido con su estado público. */
public record OrderView(
    UUID orderId,
    String companyId,
    PublicStatus status,
    FailureReason reason,
    LocalDate deliveryDate,
    String currency,
    BigDecimal total,
    List<Line> items,
    Instant createdAt,
    Instant updatedAt) {

  public record Line(String sku, int quantity, BigDecimal unitPrice) {}

  public record Page(List<OrderView> content, int page, int size, long totalElements) {}
}
