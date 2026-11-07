package com.orderflow.order.api;

import com.orderflow.order.domain.NewOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Body de {@code POST /api/orders} (SPEC §5.2). */
record CreateOrderRequest(
    @NotNull LocalDate deliveryDate,
    @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Item> items) {

  record Item(@NotBlank String sku, @NotNull @Min(1) @Max(10_000) Integer quantity) {}

  NewOrder toNewOrder() {
    return new NewOrder(
        deliveryDate, items.stream().map(i -> new NewOrder.Item(i.sku(), i.quantity())).toList());
  }
}
