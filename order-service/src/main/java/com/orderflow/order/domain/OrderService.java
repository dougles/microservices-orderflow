package com.orderflow.order.domain;

import com.orderflow.contracts.ReserveStock;
import com.orderflow.contracts.Topics;
import com.orderflow.messaging.outbox.OutboxWriter;
import com.orderflow.order.catalog.CatalogClient;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Creación de pedidos (SPEC §5.2, §8.3 fila "creación", §10 idempotencia). */
@Service
public class OrderService {

  private static final Logger log = LoggerFactory.getLogger(OrderService.class);
  private static final JsonMapper JSON = JsonMapper.shared();

  private final CatalogClient catalog;
  private final IdempotencyStore idempotency;
  private final CustomerOrderRepository orders;
  private final SagaRepository sagas;
  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Tracer tracer;
  private final Duration stepTimeout;

  OrderService(
      CatalogClient catalog,
      IdempotencyStore idempotency,
      CustomerOrderRepository orders,
      SagaRepository sagas,
      OutboxWriter outbox,
      TransactionTemplate tx,
      Clock clock,
      ObjectProvider<Tracer> tracer,
      @Value("${orderflow.saga.step-timeout}") Duration stepTimeout) {
    this.catalog = catalog;
    this.idempotency = idempotency;
    this.orders = orders;
    this.sagas = sagas;
    this.outbox = outbox;
    this.tx = tx;
    this.clock = clock;
    this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
    this.stepTimeout = stepTimeout;
  }

  /**
   * @throws InvalidOrderException regla de SPEC §5.2 incumplida
   * @throws IdempotencyKeyReusedException la key ya se usó con otro body
   * @throws UnknownSkuException algún sku desconocido o inactivo
   * @throws com.orderflow.order.catalog.CatalogUnavailableException la key no se guarda
   */
  public OrderAccepted create(String companyId, UUID key, NewOrder order) {
    OrderRules.check(order, clock);
    String hash = RequestHash.of(order);

    Optional<IdempotencyStore.Entry> previous = idempotency.find(companyId, key);
    if (previous.isPresent()) {
      return replay(previous.get(), hash);
    }

    CatalogClient.Prices prices = catalog.prices(companyId, order.skus());
    List<OrderLine> lines = price(order, prices);

    return tx.execute(status -> persist(companyId, key, hash, order, prices.currency(), lines));
  }

  private OrderAccepted persist(
      String companyId,
      UUID key,
      String hash,
      NewOrder order,
      String currency,
      List<OrderLine> lines) {
    Instant now = clock.instant();
    var accepted = new OrderAccepted(UUID.randomUUID(), PublicStatus.PENDING.name());
    var entry =
        new IdempotencyStore.Entry(
            hash, HttpStatus.ACCEPTED.value(), JSON.writeValueAsString(accepted));

    // Primero la key: un request concurrente con la misma key espera aquí a que este commitee.
    if (!idempotency.insertIfAbsent(companyId, key, entry, now)) {
      return replay(idempotency.find(companyId, key).orElseThrow(), hash);
    }

    UUID orderId = accepted.orderId();
    // SPEC §13: el span de creación lleva order.id (es el que se busca en Jaeger, F18).
    Span span = tracer.currentSpan();
    if (span != null) {
      span.tag("order.id", orderId.toString());
    }
    orders.save(new CustomerOrder(orderId, companyId, order.deliveryDate(), currency, lines, now));
    sagas.save(Saga.awaitingStock(orderId, now, stepTimeout));
    var items = order.items().stream().map(i -> new ReserveStock.Item(i.sku(), i.quantity())).toList();
    outbox.enqueueKafka(
        Topics.INVENTORY_COMMANDS, orderId.toString(), new ReserveStock(orderId, items));

    try (var ignored = MDC.putCloseable("orderId", orderId.toString());
        var ignored2 = MDC.putCloseable("companyId", companyId)) {
      log.info("pedido creado en AWAITING_STOCK");
    }
    return accepted;
  }

  private static OrderAccepted replay(IdempotencyStore.Entry entry, String hash) {
    if (!entry.requestHash().equals(hash)) {
      throw new IdempotencyKeyReusedException();
    }
    return JSON.readValue(entry.responseBody(), OrderAccepted.class);
  }

  private static List<OrderLine> price(NewOrder order, CatalogClient.Prices prices) {
    var unknown = new ArrayList<>(prices.unknownSkus());
    var lines = new ArrayList<OrderLine>();
    for (NewOrder.Item item : order.items()) {
      BigDecimal unitPrice = prices.unitPrices().get(item.sku());
      if (unitPrice == null) {
        if (!unknown.contains(item.sku())) {
          unknown.add(item.sku());
        }
      } else {
        lines.add(new OrderLine(item.sku(), item.quantity(), Money.of(unitPrice)));
      }
    }
    if (!unknown.isEmpty()) {
      throw new UnknownSkuException(unknown);
    }
    return lines;
  }
}
