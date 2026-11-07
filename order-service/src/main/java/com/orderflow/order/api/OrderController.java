package com.orderflow.order.api;

import com.orderflow.order.config.CurrentCompany;
import com.orderflow.order.domain.InvalidOrderException;
import com.orderflow.order.domain.OrderAccepted;
import com.orderflow.order.domain.OrderQueries;
import com.orderflow.order.domain.OrderService;
import com.orderflow.order.domain.PublicStatus;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
class OrderController {

  private static final Pattern UUID_FORMAT =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private final OrderService orders;
  private final OrderQueries queries;
  private final CurrentCompany company;
  private final Validator validator;

  OrderController(
      OrderService orders, OrderQueries queries, CurrentCompany company, Validator validator) {
    this.orders = orders;
    this.queries = queries;
    this.company = company;
    this.validator = validator;
  }

  /** La key se revisa antes que el body: sin key válida no se valida nada más. */
  @PostMapping
  ResponseEntity<OrderAccepted> create(
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody CreateOrderRequest request) {
    UUID key = parseKey(idempotencyKey);
    if (!validator.validate(request).isEmpty()) {
      throw new InvalidOrderException("body inválido");
    }
    OrderAccepted accepted = orders.create(company.required(), key, request.toNewOrder());
    return ResponseEntity.accepted()
        .location(URI.create("/api/orders/" + accepted.orderId()))
        .body(accepted);
  }

  @GetMapping("/{orderId}")
  OrderResponse get(@PathVariable UUID orderId) {
    return OrderResponse.of(queries.get(orderId, company.readScope()));
  }

  @GetMapping
  OrderResponse.Page list(
      @RequestParam(required = false) PublicStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return OrderResponse.Page.of(
        queries.list(company.readScope(), Optional.ofNullable(status), page, size));
  }

  private static UUID parseKey(String value) {
    if (value == null || !UUID_FORMAT.matcher(value).matches()) {
      throw new MissingIdempotencyKeyException();
    }
    return UUID.fromString(value);
  }
}
