package com.orderflow.order.domain;

import java.util.EnumSet;
import java.util.Set;

/** Estado público derivado del estado de saga y de {@code failure_reason} (SPEC §5.2). */
public enum PublicStatus {
  PENDING(
      EnumSet.of(
          SagaState.AWAITING_STOCK,
          SagaState.AWAITING_CREDIT,
          SagaState.AWAITING_SHIPMENT,
          SagaState.COMPENSATING),
      Set.of()),
  CONFIRMED(EnumSet.of(SagaState.CONFIRMED), Set.of()),
  REJECTED(
      Final.STATES,
      EnumSet.of(
          FailureReason.OUT_OF_STOCK,
          FailureReason.CREDIT_LIMIT_EXCEEDED,
          FailureReason.NO_CREDIT_LINE)),
  CANCELLED(
      Final.STATES, EnumSet.of(FailureReason.SHIPPING_NO_CAPACITY, FailureReason.STEP_TIMEOUT));

  private static final class Final {
    static final Set<SagaState> STATES =
        EnumSet.of(SagaState.REJECTED, SagaState.CANCELLED, SagaState.COMPENSATION_FAILED);
  }

  private final Set<SagaState> states;

  /** Vacío = el estado público no depende de la razón y no se expone. */
  private final Set<FailureReason> reasons;

  PublicStatus(Set<SagaState> states, Set<FailureReason> reasons) {
    this.states = states;
    this.reasons = reasons;
  }

  public Set<SagaState> states() {
    return states;
  }

  public Set<FailureReason> reasons() {
    return reasons;
  }

  /** {@code reason} público: solo los estados que dependen de la razón la exponen. */
  public FailureReason exposedReason(FailureReason reason) {
    return reasons.isEmpty() ? null : reason;
  }

  public static PublicStatus of(SagaState state, FailureReason reason) {
    for (PublicStatus status : values()) {
      if (status.states.contains(state)
          && (status.reasons.isEmpty() || status.reasons.contains(reason))) {
        return status;
      }
    }
    throw new IllegalStateException("saga " + state + " sin failure_reason válido: " + reason);
  }
}
