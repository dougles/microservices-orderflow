package com.orderflow.order.domain;

import com.orderflow.contracts.CancelShipment;
import com.orderflow.contracts.CreditRejected;
import com.orderflow.contracts.ReleaseCredit;
import com.orderflow.contracts.ReleaseStock;
import com.orderflow.contracts.ReserveCredit;
import com.orderflow.contracts.ScheduleShipment;
import com.orderflow.contracts.Topics;
import com.orderflow.messaging.outbox.OutboxWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transiciones de SPEC §8.3 y respuestas tardías de §8.4. */
@Service
public class SagaOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

  private enum Resource {
    STOCK,
    CREDIT,
    SHIPMENT
  }

  private enum ReplyKind {
    RESERVED,
    RELEASED,
    REJECTED
  }

  private final SagaRepository sagas;
  private final CustomerOrderRepository orders;
  private final OutboxWriter outbox;
  private final Clock clock;
  private final MeterRegistry registry;
  private final Duration stepTimeout;
  private final Duration compensationRetryInterval;
  private final int maxCompensationAttempts;
  private final Counter compensationFailed;
  private final Counter unexpectedReply;

  SagaOrchestrator(
      SagaRepository sagas,
      CustomerOrderRepository orders,
      OutboxWriter outbox,
      Clock clock,
      MeterRegistry registry,
      @Value("${orderflow.saga.step-timeout}") Duration stepTimeout,
      @Value("${orderflow.saga.compensation-retry-interval}") Duration compensationRetryInterval,
      @Value("${orderflow.saga.max-compensation-attempts}") int maxCompensationAttempts) {
    this.sagas = sagas;
    this.orders = orders;
    this.outbox = outbox;
    this.clock = clock;
    this.registry = registry;
    this.stepTimeout = stepTimeout;
    this.compensationRetryInterval = compensationRetryInterval;
    this.maxCompensationAttempts = maxCompensationAttempts;
    // Registrados al arrancar: increase() de la alerta necesita ver la serie antes del primer +1.
    this.compensationFailed = registry.counter("orderflow.saga.compensation.failed");
    this.unexpectedReply = registry.counter("orderflow.saga.unexpected.reply");
  }

  @Transactional
  public void onStockReserved(UUID orderId) {
    withSaga(
        orderId,
        "StockReserved",
        saga -> {
          if (saga.getState() != SagaState.AWAITING_STOCK) {
            lateOrUnexpected(saga, ReplyKind.RESERVED, Resource.STOCK, "StockReserved");
            return;
          }
          saga.markStockReserved();
          CustomerOrder order = orders.findById(orderId).orElseThrow();
          outbox.enqueueKafka(
              Topics.CREDIT_COMMANDS,
              orderId.toString(),
              new ReserveCredit(
                  orderId, order.getCompanyId(), order.getTotal(), order.getCurrency()));
          await(saga, SagaState.AWAITING_CREDIT);
        });
  }

  @Transactional
  public void onStockRejected(UUID orderId) {
    withSaga(
        orderId,
        "StockRejected",
        saga -> {
          if (saga.getState() != SagaState.AWAITING_STOCK) {
            lateOrUnexpected(saga, ReplyKind.REJECTED, Resource.STOCK, "StockRejected");
            return;
          }
          saga.setFailureReason(FailureReason.OUT_OF_STOCK);
          finish(saga, SagaState.REJECTED);
        });
  }

  @Transactional
  public void onStockReleased(UUID orderId) {
    withSaga(orderId, "StockReleased", saga -> released(saga, Resource.STOCK, "StockReleased"));
  }

  @Transactional
  public void onCreditReserved(UUID orderId) {
    withSaga(
        orderId,
        "CreditReserved",
        saga -> {
          if (saga.getState() != SagaState.AWAITING_CREDIT) {
            lateOrUnexpected(saga, ReplyKind.RESERVED, Resource.CREDIT, "CreditReserved");
            return;
          }
          saga.markCreditReserved();
          CustomerOrder order = orders.findById(orderId).orElseThrow();
          outbox.enqueueKafka(
              Topics.SHIPPING_COMMANDS,
              orderId.toString(),
              new ScheduleShipment(orderId, order.getCompanyId(), order.getDeliveryDate()));
          await(saga, SagaState.AWAITING_SHIPMENT);
        });
  }

  @Transactional
  public void onCreditRejected(UUID orderId, CreditRejected.Reason reason) {
    withSaga(
        orderId,
        "CreditRejected",
        saga -> {
          // ALREADY_RELEASED no es un failure_reason (§8.1): cae en "otra combinación" de §8.4.
          FailureReason failure =
              switch (reason) {
                case CREDIT_LIMIT_EXCEEDED -> FailureReason.CREDIT_LIMIT_EXCEEDED;
                case NO_CREDIT_LINE -> FailureReason.NO_CREDIT_LINE;
                case ALREADY_RELEASED -> null;
              };
          if (saga.getState() != SagaState.AWAITING_CREDIT || failure == null) {
            lateOrUnexpected(saga, ReplyKind.REJECTED, Resource.CREDIT, "CreditRejected");
            return;
          }
          startCompensation(saga, failure, null);
        });
  }

  @Transactional
  public void onCreditReleased(UUID orderId) {
    withSaga(orderId, "CreditReleased", saga -> released(saga, Resource.CREDIT, "CreditReleased"));
  }

  @Transactional
  public void onShipmentScheduled(UUID orderId) {
    withSaga(
        orderId,
        "ShipmentScheduled",
        saga -> {
          if (saga.getState() != SagaState.AWAITING_SHIPMENT) {
            lateOrUnexpected(saga, ReplyKind.RESERVED, Resource.SHIPMENT, "ShipmentScheduled");
            return;
          }
          finish(saga, SagaState.CONFIRMED);
        });
  }

  @Transactional
  public void onShipmentRejected(UUID orderId) {
    withSaga(
        orderId,
        "ShipmentRejected",
        saga -> {
          if (saga.getState() != SagaState.AWAITING_SHIPMENT) {
            lateOrUnexpected(saga, ReplyKind.REJECTED, Resource.SHIPMENT, "ShipmentRejected");
            return;
          }
          startCompensation(saga, FailureReason.SHIPPING_NO_CAPACITY, null);
        });
  }

  @Transactional
  public void onShipmentCancelled(UUID orderId) {
    withSaga(
        orderId,
        "ShipmentCancelled",
        saga -> released(saga, Resource.SHIPMENT, "ShipmentCancelled"));
  }

  /** Vuelve a verificar bajo lock: pudo llegar una respuesta después del escaneo. */
  @Transactional
  public void onTimeout(UUID orderId) {
    sagas.lockById(orderId).ifPresent(this::applyTimeout);
  }

  private void applyTimeout(Saga saga) {
    Instant now = clock.instant();
    if (saga.getStepDeadline() == null || saga.getStepDeadline().isAfter(now)) {
      return;
    }
    if (saga.getState().isAwaiting()) {
      Resource current =
          switch (saga.getState()) {
            case AWAITING_STOCK -> Resource.STOCK;
            case AWAITING_CREDIT -> Resource.CREDIT;
            default -> Resource.SHIPMENT;
          };
      startCompensation(saga, FailureReason.STEP_TIMEOUT, current);
    } else if (saga.getState() == SagaState.COMPENSATING) {
      retryOrFailCompensation(saga, now);
    }
  }

  private void retryOrFailCompensation(Saga saga, Instant now) {
    if (saga.getCompensationAttempts() >= maxCompensationAttempts) {
      compensationFailed.increment();
      log.error("compensación agotada orderId={}", saga.getOrderId());
      finish(saga, SagaState.COMPENSATION_FAILED);
      return;
    }
    saga.incrementCompensationAttempts();
    if (saga.isStockReleasePending()) {
      emitRelease(saga, Resource.STOCK);
    }
    if (saga.isCreditReleasePending()) {
      emitRelease(saga, Resource.CREDIT);
    }
    if (saga.isShipmentCancelPending()) {
      emitRelease(saga, Resource.SHIPMENT);
    }
    saga.moveTo(SagaState.COMPENSATING, now.plus(compensationRetryInterval), now);
  }

  /** {@code current}: recurso del paso en curso que también se libera (solo en timeout). */
  private void startCompensation(Saga saga, FailureReason reason, Resource current) {
    saga.setFailureReason(reason);
    if (saga.isStockReserved() || current == Resource.STOCK) {
      requestRelease(saga, Resource.STOCK);
    }
    if (saga.isCreditReserved() || current == Resource.CREDIT) {
      requestRelease(saga, Resource.CREDIT);
    }
    if (current == Resource.SHIPMENT) {
      requestRelease(saga, Resource.SHIPMENT);
    }
    Instant now = clock.instant();
    saga.moveTo(SagaState.COMPENSATING, now.plus(compensationRetryInterval), now);
  }

  private void released(Saga saga, Resource resource, String reply) {
    if (saga.getState() != SagaState.COMPENSATING) {
      lateOrUnexpected(saga, ReplyKind.RELEASED, resource, reply);
      return;
    }
    switch (resource) {
      case STOCK -> saga.setStockReleasePending(false);
      case CREDIT -> saga.setCreditReleasePending(false);
      case SHIPMENT -> saga.setShipmentCancelPending(false);
    }
    if (!saga.hasPendingReleases()) {
      boolean rejected = PublicStatus.REJECTED.reasons().contains(saga.getFailureReason());
      finish(saga, rejected ? SagaState.REJECTED : SagaState.CANCELLED);
    }
  }

  /** Única implementación de SPEC §8.4. Nunca lanza: una respuesta rara no debe ir al DLT. */
  private void lateOrUnexpected(Saga saga, ReplyKind kind, Resource resource, String reply) {
    SagaState state = saga.getState();
    boolean compensating = state == SagaState.COMPENSATING;
    switch (kind) {
      case RESERVED -> {
        if (compensating) {
          requestRelease(saga, resource);
          return;
        }
        if (state.isFinal() && state != SagaState.CONFIRMED) {
          emitRelease(saga, resource);
          return;
        }
      }
      case RELEASED -> {
        if (state.isFinal()) {
          return;
        }
      }
      case REJECTED -> {
        if (compensating || state.isFinal()) {
          return;
        }
      }
    }
    unexpected(saga.getOrderId(), reply, state);
  }

  private void requestRelease(Saga saga, Resource resource) {
    switch (resource) {
      case STOCK -> saga.setStockReleasePending(true);
      case CREDIT -> saga.setCreditReleasePending(true);
      case SHIPMENT -> saga.setShipmentCancelPending(true);
    }
    emitRelease(saga, resource);
  }

  private void emitRelease(Saga saga, Resource resource) {
    UUID orderId = saga.getOrderId();
    String key = orderId.toString();
    switch (resource) {
      case STOCK -> outbox.enqueueKafka(Topics.INVENTORY_COMMANDS, key, new ReleaseStock(orderId));
      case CREDIT -> outbox.enqueueKafka(Topics.CREDIT_COMMANDS, key, new ReleaseCredit(orderId));
      case SHIPMENT ->
          outbox.enqueueKafka(Topics.SHIPPING_COMMANDS, key, new CancelShipment(orderId));
    }
  }

  private void await(Saga saga, SagaState next) {
    Instant now = clock.instant();
    saga.moveTo(next, now.plus(stepTimeout), now);
  }

  private void finish(Saga saga, SagaState finalState) {
    saga.moveTo(finalState, null, clock.instant());
    registry.counter("orderflow.saga.finished", "state", finalState.name()).increment();
    log.info("saga finalizada orderId={} state={}", saga.getOrderId(), finalState);
  }

  private void withSaga(UUID orderId, String reply, Consumer<Saga> action) {
    sagas.lockById(orderId).ifPresentOrElse(action, () -> unexpected(orderId, reply, null));
  }

  private void unexpected(UUID orderId, String reply, SagaState state) {
    unexpectedReply.increment();
    log.warn("respuesta inesperada {} orderId={} state={}", reply, orderId, state);
  }
}
