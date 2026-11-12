package com.orderflow.order.api;

import com.orderflow.order.domain.Money;
import com.orderflow.order.domain.OrderView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** JSON de un pedido (SPEC §5.2). Montos como string con 2 decimales. */
record OrderResponse(
    UUID orderId,
    String companyId,
    String status,
    String reason,
    LocalDate deliveryDate,
    String currency,
    String total,
    List<Item> items,
    Instant createdAt,
    Instant updatedAt) {

  record Item(String sku, int quantity, String unitPrice) {}

  record Page(List<OrderResponse> content, int page, int size, long totalElements) {

    static Page of(OrderView.Page page) {
      return new Page(
          page.content().stream().map(OrderResponse::of).toList(),
          page.page(),
          page.size(),
          page.totalElements());
    }
  }

  static OrderResponse of(OrderView view) {
    return new OrderResponse(
        view.orderId(),
        view.companyId(),
        view.status().name(),
        view.reason() == null ? null : view.reason().name(),
        view.deliveryDate(),
        view.currency(),
        Money.format(view.total()),
        view.items().stream()
            .map(l -> new Item(l.sku(), l.quantity(), Money.format(l.unitPrice())))
            .toList(),
        view.createdAt(),
        view.updatedAt());
  }
}
