package com.orderflow.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orderflow.contracts.CancelShipment;
import com.orderflow.contracts.CreditRejected;
import com.orderflow.contracts.ReleaseCredit;
import com.orderflow.contracts.ReleaseStock;
import com.orderflow.contracts.ReserveCredit;
import com.orderflow.contracts.ScheduleShipment;
import com.orderflow.contracts.Topics;
import com.orderflow.messaging.outbox.OutboxWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Todas las filas de SPEC §8.3 y los casos de §8.4. */
class SagaOrchestratorTest {

  private static final Duration STEP = Duration.ofMinutes(5);
  private static final Duration RETRY = Duration.ofMinutes(1);
  private static final int MAX_ATTEMPTS = 5;

  private record Emitted(String topic, Object message) {}

  private static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final UUID id = UUID.randomUUID();
  private final MutableClock clock = new MutableClock();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final List<Emitted> emitted = new ArrayList<>();
  private SagaOrchestrator orchestrator;
  private Saga saga;

  @BeforeEach
  void setUp() {
    SagaRepository sagas = mock(SagaRepository.class);
    CustomerOrderRepository orders = mock(CustomerOrderRepository.class);
    OutboxWriter outbox = mock(OutboxWriter.class);
    saga = Saga.awaitingStock(id, clock.now, STEP);
    when(sagas.lockById(id)).thenReturn(Optional.of(saga));
    var order =
        new CustomerOrder(
            id,
            "ACME",
            LocalDate.of(2026, 2, 1),
            "BOB",
            List.of(new OrderLine("SKU-1", 2, new BigDecimal("10.00"))),
            clock.now);
    when(orders.findById(id)).thenReturn(Optional.of(order));
    when(outbox.enqueueKafka(anyString(), anyString(), any()))
        .thenAnswer(
            call -> {
              emitted.add(new Emitted(call.getArgument(0), call.getArgument(2)));
              return UUID.randomUUID();
            });
    orchestrator =
        new SagaOrchestrator(sagas, orders, outbox, clock, registry, STEP, RETRY, MAX_ATTEMPTS);
  }

  private void toAwaitingCredit() {
    orchestrator.onStockReserved(id);
    emitted.clear();
  }

  private void toAwaitingShipment() {
    toAwaitingCredit();
    orchestrator.onCreditReserved(id);
    emitted.clear();
  }

  /** ShipmentRejected: quedan pendientes ReleaseCredit y ReleaseStock. */
  private void toCompensating() {
    toAwaitingShipment();
    orchestrator.onShipmentRejected(id);
    emitted.clear();
  }

  private void toCancelled() {
    toCompensating();
    orchestrator.onStockReleased(id);
    orchestrator.onCreditReleased(id);
    emitted.clear();
  }

  private void expireDeadline() {
    clock.now = saga.getStepDeadline().plusSeconds(1);
  }

  private List<Object> messages() {
    return emitted.stream().map(Emitted::message).toList();
  }

  private double counter(String name) {
    return registry.counter(name).count();
  }

  private double finished(SagaState state) {
    return registry.counter("orderflow.saga.finished", "state", state.name()).count();
  }

  // --- SPEC §8.3 ---

  @Test
  void stockReservadoEmiteReserveCredit() {
    orchestrator.onStockReserved(id);

    assertThat(saga.getState()).isEqualTo(SagaState.AWAITING_CREDIT);
    assertThat(saga.isStockReserved()).isTrue();
    assertThat(saga.getStepDeadline()).isEqualTo(clock.now.plus(STEP));
    assertThat(emitted)
        .containsExactly(
            new Emitted(
                Topics.CREDIT_COMMANDS,
                new ReserveCredit(id, "ACME", new BigDecimal("20.00"), "BOB")));
  }

  @Test
  void stockRechazadoFinalizaRechazado() {
    orchestrator.onStockRejected(id);

    assertThat(saga.getState()).isEqualTo(SagaState.REJECTED);
    assertThat(saga.getFailureReason()).isEqualTo(FailureReason.OUT_OF_STOCK);
    assertThat(saga.getStepDeadline()).isNull();
    assertThat(emitted).isEmpty();
    assertThat(finished(SagaState.REJECTED)).isEqualTo(1);
  }

  @Test
  void creditoReservadoEmiteScheduleShipment() {
    toAwaitingCredit();

    orchestrator.onCreditReserved(id);

    assertThat(saga.getState()).isEqualTo(SagaState.AWAITING_SHIPMENT);
    assertThat(saga.isCreditReserved()).isTrue();
    assertThat(saga.getStepDeadline()).isEqualTo(clock.now.plus(STEP));
    assertThat(emitted)
        .containsExactly(
            new Emitted(
                Topics.SHIPPING_COMMANDS,
                new ScheduleShipment(id, "ACME", LocalDate.of(2026, 2, 1))));
  }

  @Test
  void creditoRechazadoCompensaElStock() {
    toAwaitingCredit();

    orchestrator.onCreditRejected(id, CreditRejected.Reason.CREDIT_LIMIT_EXCEEDED);

    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
    assertThat(saga.getFailureReason()).isEqualTo(FailureReason.CREDIT_LIMIT_EXCEEDED);
    assertThat(saga.isStockReleasePending()).isTrue();
    assertThat(saga.isCreditReleasePending()).isFalse();
    assertThat(saga.getStepDeadline()).isEqualTo(clock.now.plus(RETRY));
    assertThat(messages()).containsExactly(new ReleaseStock(id));
  }

  @Test
  void sinLineaDeCreditoConservaLaRazon() {
    toAwaitingCredit();

    orchestrator.onCreditRejected(id, CreditRejected.Reason.NO_CREDIT_LINE);

    assertThat(saga.getFailureReason()).isEqualTo(FailureReason.NO_CREDIT_LINE);
  }

  @Test
  void despachoAgendadoConfirma() {
    toAwaitingShipment();

    orchestrator.onShipmentScheduled(id);

    assertThat(saga.getState()).isEqualTo(SagaState.CONFIRMED);
    assertThat(saga.getStepDeadline()).isNull();
    assertThat(finished(SagaState.CONFIRMED)).isEqualTo(1);
  }

  @Test
  void despachoRechazadoCompensaCreditoYStock() {
    toAwaitingShipment();

    orchestrator.onShipmentRejected(id);

    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
    assertThat(saga.getFailureReason()).isEqualTo(FailureReason.SHIPPING_NO_CAPACITY);
    assertThat(messages())
        .containsExactlyInAnyOrder(new ReleaseStock(id), new ReleaseCredit(id));
  }

  @Test
  void timeoutEnStockLiberaElPasoEnCurso() {
    expireDeadline();

    orchestrator.onTimeout(id);

    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
    assertThat(saga.getFailureReason()).isEqualTo(FailureReason.STEP_TIMEOUT);
    assertThat(saga.isStockReleasePending()).isTrue();
    assertThat(messages()).containsExactly(new ReleaseStock(id));
  }

  @Test
  void timeoutEnCreditoLiberaStockYCredito() {
    toAwaitingCredit();
    expireDeadline();

    orchestrator.onTimeout(id);

    assertThat(messages())
        .containsExactlyInAnyOrder(new ReleaseStock(id), new ReleaseCredit(id));
  }

  @Test
  void timeoutEnDespachoLiberaTodo() {
    toAwaitingShipment();
    expireDeadline();

    orchestrator.onTimeout(id);

    assertThat(saga.isShipmentCancelPending()).isTrue();
    assertThat(messages())
        .containsExactlyInAnyOrder(
            new ReleaseStock(id), new ReleaseCredit(id), new CancelShipment(id));
  }

  @Test
  void timeoutAntesDelDeadlineNoHaceNada() {
    orchestrator.onTimeout(id);

    assertThat(saga.getState()).isEqualTo(SagaState.AWAITING_STOCK);
    assertThat(emitted).isEmpty();
  }

  @Test
  void compensacionTerminaCuandoNoQuedanPendientes() {
    toCompensating();

    orchestrator.onStockReleased(id);
    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);

    orchestrator.onCreditReleased(id);
    assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
    assertThat(saga.getStepDeadline()).isNull();
    assertThat(finished(SagaState.CANCELLED)).isEqualTo(1);
  }

  @Test
  void compensacionPorCreditoTerminaRechazada() {
    toAwaitingCredit();
    orchestrator.onCreditRejected(id, CreditRejected.Reason.CREDIT_LIMIT_EXCEEDED);

    orchestrator.onStockReleased(id);

    assertThat(saga.getState()).isEqualTo(SagaState.REJECTED);
  }

  @Test
  void compensacionPorTimeoutCancelaYCancelaElDespacho() {
    toAwaitingShipment();
    expireDeadline();
    orchestrator.onTimeout(id);

    orchestrator.onStockReleased(id);
    orchestrator.onCreditReleased(id);
    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);

    orchestrator.onShipmentCancelled(id);
    assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
  }

  @Test
  void deadlineEnCompensacionReemiteSoloPendientes() {
    toCompensating();
    orchestrator.onStockReleased(id);
    expireDeadline();

    orchestrator.onTimeout(id);

    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
    assertThat(saga.getCompensationAttempts()).isEqualTo(1);
    assertThat(saga.getStepDeadline()).isEqualTo(clock.now.plus(RETRY));
    assertThat(messages()).containsExactly(new ReleaseCredit(id));
  }

  @Test
  void intentosAgotadosFinalizanCompensationFailed() {
    toCompensating();
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      expireDeadline();
      orchestrator.onTimeout(id);
    }
    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
    emitted.clear();
    expireDeadline();

    orchestrator.onTimeout(id);

    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATION_FAILED);
    assertThat(saga.getFailureReason()).isEqualTo(FailureReason.SHIPPING_NO_CAPACITY);
    assertThat(saga.getStepDeadline()).isNull();
    assertThat(emitted).isEmpty();
    assertThat(counter("orderflow.saga.compensation.failed")).isEqualTo(1);
    assertThat(finished(SagaState.COMPENSATION_FAILED)).isEqualTo(1);
  }

  // --- SPEC §8.4 ---

  @Test
  void creditoReservadoTardioEnCompensacionSeMarcaYSeLibera() {
    toCompensating();
    orchestrator.onCreditReleased(id);
    assertThat(saga.isCreditReleasePending()).isFalse();

    orchestrator.onCreditReserved(id);

    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
    assertThat(saga.isCreditReleasePending()).isTrue();
    assertThat(messages()).containsExactly(new ReleaseCredit(id));
  }

  @Test
  void stockReservadoTardioEnCompensacion() {
    toCompensating();
    orchestrator.onStockReleased(id);

    orchestrator.onStockReserved(id);

    assertThat(saga.isStockReleasePending()).isTrue();
    assertThat(messages()).containsExactly(new ReleaseStock(id));
  }

  @Test
  void despachoAgendadoTardioEnCompensacion() {
    toCompensating();

    orchestrator.onShipmentScheduled(id);

    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
    assertThat(saga.isShipmentCancelPending()).isTrue();
    assertThat(messages()).containsExactly(new CancelShipment(id));
  }

  @Test
  void reservaTardiaEnEstadoFinalLiberaSinCambiarEstado() {
    toCancelled();

    orchestrator.onStockReserved(id);
    orchestrator.onCreditReserved(id);
    orchestrator.onShipmentScheduled(id);

    assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
    assertThat(saga.hasPendingReleases()).isFalse();
    assertThat(messages())
        .containsExactly(new ReleaseStock(id), new ReleaseCredit(id), new CancelShipment(id));
  }

  @Test
  void liberadosEnEstadoFinalSeIgnoran() {
    toCancelled();

    orchestrator.onStockReleased(id);
    orchestrator.onCreditReleased(id);
    orchestrator.onShipmentCancelled(id);

    assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
    assertThat(emitted).isEmpty();
    assertThat(counter("orderflow.saga.unexpected.reply")).isZero();
  }

  @Test
  void rechazadosEnCompensacionOFinalSeIgnoran() {
    toCompensating();

    orchestrator.onStockRejected(id);
    orchestrator.onCreditRejected(id, CreditRejected.Reason.CREDIT_LIMIT_EXCEEDED);
    orchestrator.onShipmentRejected(id);

    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
    assertThat(emitted).isEmpty();
    assertThat(counter("orderflow.saga.unexpected.reply")).isZero();

    orchestrator.onStockReleased(id);
    orchestrator.onCreditReleased(id);
    orchestrator.onStockRejected(id);

    assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
    assertThat(counter("orderflow.saga.unexpected.reply")).isZero();
  }

  @Test
  void combinacionNoListadaCuentaYNoCambiaNada() {
    orchestrator.onCreditReserved(id);
    orchestrator.onShipmentScheduled(id);
    orchestrator.onStockReleased(id);

    assertThat(saga.getState()).isEqualTo(SagaState.AWAITING_STOCK);
    assertThat(emitted).isEmpty();
    assertThat(counter("orderflow.saga.unexpected.reply")).isEqualTo(3);
  }

  @Test
  void reservaEnConfirmadoEsInesperadaYNoLibera() {
    toAwaitingShipment();
    orchestrator.onShipmentScheduled(id);

    orchestrator.onStockReserved(id);

    assertThat(saga.getState()).isEqualTo(SagaState.CONFIRMED);
    assertThat(emitted).isEmpty();
    assertThat(counter("orderflow.saga.unexpected.reply")).isEqualTo(1);
  }

  @Test
  void creditoAlreadyReleasedEnEsperaEsInesperado() {
    toAwaitingCredit();

    orchestrator.onCreditRejected(id, CreditRejected.Reason.ALREADY_RELEASED);

    assertThat(saga.getState()).isEqualTo(SagaState.AWAITING_CREDIT);
    assertThat(counter("orderflow.saga.unexpected.reply")).isEqualTo(1);
  }

  @Test
  void sagaInexistenteCuentaComoInesperada() {
    orchestrator.onStockReserved(UUID.randomUUID());

    assertThat(counter("orderflow.saga.unexpected.reply")).isEqualTo(1);
  }
}
