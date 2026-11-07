package com.orderflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InvoiceJob(
    UUID orderId,
    String companyId,
    List<Line> lines,
    BigDecimal total,
    String currency,
    Instant confirmedAt) {

  public record Line(String sku, int quantity, BigDecimal unitPrice) {}
}
